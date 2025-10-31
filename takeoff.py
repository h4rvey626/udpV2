# -*- coding: utf-8 -*-
from __future__ import print_function
"""
键盘交互起飞（Python 2.7 版）
- GPS 模式：GUIDED + simple_takeoff 到目标高度，并可在原地调整高度
- 无GPS 模式：GUIDED_NOGPS + TAKEOFF 到目标高度（无法定点，会有水平漂移）
用法:
  python takeoff_keyboard_py27.py --connect udp:127.0.0.1:14551 --mode gps --alt 3
命令:
  t  起飞到目标高度(default target altitude 2m)
  u  目标高度 +0.5m（gps模式会重新引导到当前点的新高度）
  j  目标高度 -0.5m
  l  降落（切 LAND）
  a  解锁/上锁
  q  退出
"""
from dronekit import connect, VehicleMode, LocationGlobalRelative, APIException
from pymavlink import mavutil
import argparse
import time

def get_args():
    p = argparse.ArgumentParser(description="Keyboard takeoff test (Py2)")
    p.add_argument("--connect", help="Vehicle connection string, e.g. udp:127.0.0.1:14551 or serial:/dev/ttyACM0:115200")
    p.add_argument("--mode", choices=["gps", "nogps"], default="gps", help="gps=GUIDED, nogps=GUIDED_NOGPS")
    p.add_argument("--alt", type=float, default=2.0, help="Target altitude (m)")
    return p.parse_args()

def connect_vehicle(conn):
    print("Connecting to: %s" % (conn or "udp:127.0.0.1:14551"))
    v = connect(conn or "udp:127.0.0.1:14551", wait_ready=True, timeout=60)
    print("Connected. FW: %s  Battery: %s" % (str(v.version), str(v.battery)))
    return v

def wait_mode(vehicle, name, timeout=10):
    t0 = time.time()
    while vehicle.mode.name != name and time.time() - t0 < timeout:
        time.sleep(0.1)
    return vehicle.mode.name == name

def ensure_guided(vehicle, use_gps):
    target = "GUIDED" if use_gps else "GUIDED_NOGPS"
    if vehicle.mode.name != target:
        vehicle.mode = VehicleMode(target)
        if not wait_mode(vehicle, target, 10):
            raise RuntimeError("切换到 %s 失败" % target)

def arm(vehicle):
    if vehicle.armed:
        print("已解锁")
        return
    print("解锁中...")
    vehicle.armed = True
    t0 = time.time()
    while not vehicle.armed and time.time() - t0 < 10:
        time.sleep(0.2)
    if not vehicle.armed:
        raise RuntimeError("解锁失败")

def disarm(vehicle):
    if not vehicle.armed:
        print("已上锁")
        return
    print("上锁中...")
    vehicle.armed = False
    t0 = time.time()
    while vehicle.armed and time.time() - t0 < 10:
        time.sleep(0.2)
    if vehicle.armed:
        raise RuntimeError("上锁失败")

def simple_takeoff_gps(vehicle, alt):
    """
    GUIDED + simple_takeoff；需要 EKF OK 且 GPS 3D Fix
    """
    if hasattr(vehicle, "ekf_ok") and not vehicle.ekf_ok:
        print("警告：EKF 未就绪，起飞可能被拒绝")
    if hasattr(vehicle, "gps_0") and getattr(vehicle.gps_0, "fix_type", 0) < 3:
        raise RuntimeError("GPS 未3D Fix，无法在 GUIDED 起飞")

    ensure_guided(vehicle, use_gps=True)
    arm(vehicle)
    print("起飞到 %.2fm ..." % alt)
    vehicle.simple_takeoff(alt)

def takeoff_nogps(vehicle, alt):
    """
    GUIDED_NOGPS + TAKEOFF；不需要GPS，但无法定点，会水平漂移
    """
    ensure_guided(vehicle, use_gps=False)
    arm(vehicle)
    print("无GPS起飞到 %.2fm ..." % alt)
    msg = vehicle.message_factory.command_long_encode(
        0, 0,
        mavutil.mavlink.MAV_CMD_NAV_TAKEOFF,
        0,
        0, 0, 0, 0,
        0, 0,             # lat/lon ignored in NOGPS
        float(alt)
    )
    vehicle.send_mavlink(msg)
    vehicle.flush()

def goto_same_latlon_alt(vehicle, alt):
    """
    在GPS模式下，把目标高度调整到当前经纬度的指定相对高度
    """
    loc = vehicle.location.global_relative_frame
    if loc is None or loc.lat is None:
        print("无法获取当前位置，经纬度无效")
        return
    target = LocationGlobalRelative(loc.lat, loc.lon, float(alt))
    print("引导到当前点新高度：%.2fm" % alt)
    vehicle.simple_goto(target)

def print_status(vehicle):
    loc_rel = getattr(getattr(vehicle, "location", None), "global_relative_frame", None)
    alt = getattr(loc_rel, "alt", None)
    gps = getattr(vehicle, "gps_0", None)
    fix = getattr(gps, "fix_type", None) if gps else None
    sats = getattr(gps, "satellites_visible", None) if gps else None
    ekf = getattr(vehicle, "ekf_ok", "?")
    print("Mode:%s Armed:%s Alt:%.2fm EKF:%s GPSfix:%s sats:%s" %
          (vehicle.mode.name, vehicle.armed, (alt or 0.0), ekf, fix, sats))

def main():
    args = get_args()
    v = None
    try:
        v = connect_vehicle(args.connect)

        print("\n安全提醒：请在空旷环境，RC 随时可接管。")
        print("初始目标高度：%.2fm，模式：%s" % (args.alt, "GPS/GUIDED" if args.mode == "gps" else "无GPS/GUIDED_NOGPS"))
        target_alt = float(args.alt)

        try:
            ensure_guided(v, use_gps=(args.mode == "gps"))
        except Exception as e:
            print("提示：预切模式失败，后续命令会再次尝试。原因：%s" % str(e))

        print("\n命令：t起飞  u高度+0.5  j高度-0.5  l降落  a解/上锁  q退出")
        while True:
            print_status(v)
            cmd = raw_input("> ").strip().lower()

            if cmd == "t":
                if args.mode == "gps":
                    simple_takeoff_gps(v, target_alt)
                else:
                    takeoff_nogps(v, target_alt)

            elif cmd == "u":
                target_alt += 0.5
                print("目标高度 -> %.2fm" % target_alt)
                if args.mode == "gps":
                    goto_same_latlon_alt(v, target_alt)

            elif cmd == "j":
                target_alt = max(0.5, target_alt - 0.5)
                print("目标高度 -> %.2fm" % target_alt)
                if args.mode == "gps":
                    goto_same_latlon_alt(v, target_alt)

            elif cmd == "l":
                print("切换 LAND ...")
                v.mode = VehicleMode("LAND")

            elif cmd == "a":
                if v.armed:
                    try:
                        disarm(v)
                    except Exception as e:
                        print("上锁失败：%s" % str(e))
                else:
                    try:
                        arm(v)
                    except Exception as e:
                        print("解锁失败：%s" % str(e))

            elif cmd == "q":
                break
            else:
                print("未知命令。")

            time.sleep(0.2)

    except KeyboardInterrupt:
        print("\n用户中断。")
    except APIException as e:
        print("DroneKit API 错误: %s" % e)
    except Exception as e:
        print("异常: %s" % str(e))
    finally:
        if v is not None:
            try:
                v.close()
            except Exception:
                pass
        print("退出。")

if __name__ == "__main__":
    main()
