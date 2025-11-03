# -*- coding: utf-8 -*-
#
# 简化打印调试版 WebSocket/HTTP Drone Server (Python 2.7 兼容)
#
# 功能:
#   - 连接飞控 (DroneKit)
#   - HTTP /api/status 获取基础状态
#   - WebSocket:
#       /ws/control   接收控制指令 (arm / disarm / mode / takeoff / land / joystick)
#       /ws/telemetry 推送遥测 (mode / armed / altitude / groundspeed / heading / battery / 延迟等)
#   - 简单速度控制: 发送 NED 速度指令 (GUIDED 模式)
#   - takeoff: 使用 simple_takeoff + watcher
#   - 失败调试: ensure_mode / arm 失败时打印可能阻塞信息 + 最近 STATUSTEXT
#
# 说明:
#   1. 尽量保持最少依赖和 Python 2.7 语法 (无 f-string, 无 daemon=)
#   2. 如果要在室内无 GPS 测试，可自行在 takeoff 处启用 GUIDED_NOGPS 回退（已注释）
#   3. 速度指令默认 10Hz；若 0.5s 内未收到摇杆消息则自动清零
#
# 依赖:
#   pip install dronekit tornado pymavlink
#
# 启动:
#   python drone_server.py
#
# 环境变量:
#   DRONE_CONN (例如 /dev/ttyUSB0 或 udp:127.0.0.1:14550)
#   DRONE_BAUD (默认 921600, 仅串口)
#
# 注意安全:
#   仅在测试与可控环境使用；请依据实际飞行法规与安全规范操作。

from __future__ import print_function
import os
import time
import json
import math
import threading
import collections

import tornado.ioloop
import tornado.web
import tornado.websocket

from dronekit import connect, VehicleMode, LocationGlobalRelative

# ========== 全局 ==========
vehicle = None

# 控制 & 遥测客户端集合
control_clients = set()
telemetry_clients = set()

# 最近一次摇杆命令
last_velocity_cmd = {"vx": 0.0, "vy": 0.0, "vz": 0.0, "yaw_rate": 0.0}
last_joystick_time = 0.0

# Takeoff 过程标志
takeoff_in_progress = False
takeoff_target_alt = None

# Landing 过程标志
landing_in_progress = False

# 保存最近 STATUSTEXT
recent_statustext = collections.deque(maxlen=50)

# 速度控制频率 / 超时
CONTROL_HZ = 20.0
VELOCITY_TIMEOUT = 0.5  # s

# Telemetry 发送间隔
TELEM_INTERVAL = 0.5  # s

# 默认起飞高度
DEFAULT_TAKEOFF_ALT = 1.5

# ========== 工具函数 ==========

def log(msg):
    print("[SERVER] %s" % msg)

def safe_alt():
    try:
        if vehicle and vehicle.location and vehicle.location.global_relative_frame:
            a = vehicle.location.global_relative_frame.alt
            if a is not None:
                return float(a)
    except:
        pass
    return 0.0

def get_basic_status():
    """简单状态字典（用于打印/HTTP）"""
    if not vehicle:
        return {}
    st = {}
    try:
        st['mode'] = vehicle.mode.name
    except:
        st['mode'] = None
    try:
        st['armed'] = vehicle.armed
    except:
        st['armed'] = None
    try:
        st['is_armable'] = vehicle.is_armable
    except:
        st['is_armable'] = None
    try:
        st['ekf_ok'] = vehicle.ekf_ok
    except:
        st['ekf_ok'] = None
    try:
        gps = vehicle.gps_0
        st['gps_fix'] = getattr(gps, 'fix_type', None)
        st['satellites'] = getattr(gps, 'satellites_visible', None)
    except:
        st['gps_fix'] = None
        st['satellites'] = None
    st['altitude'] = safe_alt()
    try:
        st['groundspeed'] = vehicle.groundspeed
    except:
        st['groundspeed'] = None
    try:
        st['heading'] = vehicle.heading
    except:
        st['heading'] = None
    # 添加姿态信息
    try:
        if hasattr(vehicle, 'attitude'):
            st['attitude'] = {
                'roll': vehicle.attitude.roll,
                'pitch': vehicle.attitude.pitch,
                'yaw': vehicle.attitude.yaw
            }
    except:
        st['attitude'] = None
    try:
        st['battery'] = {
            "voltage": vehicle.battery.voltage,
            "current": vehicle.battery.current,
            "level": vehicle.battery.level
        }
    except:
        st['battery'] = None
    st['takeoff_in_progress'] = takeoff_in_progress
    st['takeoff_target_alt'] = takeoff_target_alt
    st['landing_in_progress'] = landing_in_progress
    
    # 添加控制状态指示
    control_status = {
        "can_control": False,
        "current_mode": None,
        "message": ""
    }
    
    if vehicle and vehicle.armed:
        try:
            mode_name = vehicle.mode.name
            control_status["current_mode"] = mode_name
            
            if mode_name in ("GUIDED", "GUIDED_NOGPS", "POSHOLD"):
                control_status["can_control"] = True
                control_status["message"] = "摇杆控制已启用"
            elif mode_name == "LOITER":
                control_status["can_control"] = False
                control_status["message"] = "当前在 LOITER 模式，请切换到 GUIDED 模式以启用摇杆控制"
            elif mode_name == "LAND":
                control_status["can_control"] = False
                control_status["message"] = "当前在 LAND 模式，降落过程中摇杆不可用"
            else:
                control_status["can_control"] = False
                control_status["message"] = "当前模式不支持摇杆控制"
        except:
            control_status["message"] = "无法获取飞行模式"
    
    st['control_status'] = control_status
    return st

def print_blockers(prefix):
    """打印阻塞模式切换或解锁的可能因素"""
    st = get_basic_status()
    print("=== %s 状态快照 ===" % prefix)
    print("  模式: %s  armed:%s  is_armable:%s  ekf_ok:%s" % (
        st.get('mode'), st.get('armed'), st.get('is_armable'), st.get('ekf_ok')))
    print("  GPS: fix=%s  sats=%s" % (st.get('gps_fix'), st.get('satellites')))
    print("  高度: %s  地速: %s  航向: %s" % (st.get('altitude'), st.get('groundspeed'), st.get('heading')))
    print("  电池: %s" % st.get('battery'))
    if recent_statustext:
        print("  最近 STATUSTEXT（最多 5 条）:")
        for t,se,txt in list(recent_statustext)[-5:]:
            print("    sev=%s  %s" % (se, txt))
    else:
        print("  (无 STATUSTEXT)")
    # 简单提示
    if st.get('gps_fix', 0) < 3:
        print("  可能原因: GPS 未获得 3D 定位 (fix<3)")
    if st.get('ekf_ok') is False:
        print("  可能原因: EKF 未完成初始化")
    if st.get('is_armable') is False:
        print("  可能原因: 飞控 is_armable=False (等待传感器/安全检查)")
    print("=== 结束 ===")

def ensure_mode(target, attempts=3, wait_each=6.0):
    """
    极简模式切换: 多次尝试, 失败打印阻塞信息.
    返回 True/False
    """
    if not vehicle:
        print("ensure_mode(%s): vehicle 不存在" % target)
        return False

    target = str(target).upper()
    try:
        if vehicle.mode.name == target:
            return True
    except:
        pass

    for i in range(attempts):
        print("[MODE] 尝试切换到 %s (第 %d 次)" % (target, i+1))
        try:
            vehicle.mode = VehicleMode(target)
        except Exception as e:
            print("[MODE] 赋值 mode 异常: %s" % e)

        t0 = time.time()
        ok = False
        while time.time() - t0 < wait_each:
            try:
                if vehicle.mode.name == target:
                    ok = True
                    break
            except:
                pass
            time.sleep(0.5)

        if ok:
            print("[MODE] 切换到 %s 成功" % target)
            return True
        else:
            print("[MODE] 仍未进入 %s" % target)
            print_blockers("模式切换失败详情")

    print("[MODE] 多次切换 %s 失败，放弃" % target)
    return False

def arm_vehicle():
    if not vehicle:
        print("arm: vehicle 不可用")
        return False
    if vehicle.armed:
        print("已解锁")
        return True
    print("解锁中...")
    vehicle.armed = True
    t0 = time.time()
    while not vehicle.armed and time.time() - t0 < 10:
        time.sleep(0.3)
    if vehicle.armed:
        print("解锁成功")
        return True
    print("解锁失败")
    print_blockers("解锁失败诊断")
    return False

def disarm_vehicle():
    if not vehicle:
        print("disarm: vehicle 不可用")
        return False
    if not vehicle.armed:
        print("已上锁")
        return True
    # 可加高度限制
    if safe_alt() > 0.2:
        print("警告: 当前高度 %.2f m, 拒绝上锁" % safe_alt())
        return False
    print("上锁中...")
    vehicle.armed = False
    t0 = time.time()
    while vehicle.armed and time.time() - t0 < 8:
        time.sleep(0.3)
    if not vehicle.armed:
        print("上锁成功")
        return True
    print("上锁失败")
    return False

def _statustext_listener(self, name, msg):
    txt = getattr(msg, 'text', '').replace('\x00', '')
    recent_statustext.append((time.time(), getattr(msg, 'severity', None), txt))

# ========== 速度指令 (GUIDED) ==========

def send_ned_velocity(vx, vy, vz, yaw_rate=0.0):
    """
    发送 NED 速度 (m/s). yaw_rate 暂忽略或用后续扩展.
    仅在 GUIDED 下有效.
    """
    if not vehicle:
        return
    try:
        # MAVLink set_position_target_local_ned
        # 参考: MAV_FRAME_LOCAL_NED, type_mask 忽略位置只用速度
        msg = vehicle.message_factory.set_position_target_local_ned_encode(
            0,       # time_boot_ms
            0, 0,    # target system, target component
            1,       # MAV_FRAME_LOCAL_NED
            0b0000111111000111,  # type_mask (只启用速度分量 + yaw_rate 可选)
            0, 0, 0,             # x, y, z positions (unused)
            vx, vy, vz,          # x, y, z velocity
            0, 0, 0,             # accelerations (unused)
            0,                   # yaw (unused)
            0                    # yaw_rate (暂不给)
        )
        vehicle.send_mavlink(msg)
    except Exception as e:
        # 避免刷屏
        pass

# ========== TAKEOFF ==========

def do_takeoff(target_alt):
    global takeoff_in_progress, takeoff_target_alt
    if not vehicle:
        print("[TAKEOFF] vehicle 不存在")
        return
    if safe_alt() > 0.5:
        print("[TAKEOFF] 认为已在空中 (alt=%.2f)" % safe_alt())
        return

    # 进入 GUIDED
    if not ensure_mode("GUIDED", "GUIDED_NOGPS"):

        print("[TAKEOFF] 无法进入 GUIDED，放弃")
        return

    if not arm_vehicle():
        print("[TAKEOFF] 解锁失败，放弃")
        return

    print("[TAKEOFF] simple_takeoff(%.2f)" % target_alt)
    try:
        vehicle.simple_takeoff(target_alt)
    except Exception as e:
        print("[TAKEOFF] simple_takeoff 调用异常: %s" % e)
        return

    takeoff_in_progress = True
    takeoff_target_alt = target_alt

    def watcher():
        global takeoff_in_progress, takeoff_target_alt
        t0 = time.time()
        
        # 完全模仿 takeoff.py：只监视不干预
        while time.time() - t0 < 30:  # 30秒超时
            alt = safe_alt()
            if alt >= target_alt * 0.98:  # 提高精度要求到98%
                print("[TAKEOFF] 达到目标高度 %.2f (当前 %.2f)" % (target_alt, alt))
                # 等待额外2秒让飞机自然稳定
                time.sleep(2.0)
                break
            time.sleep(0.5)  # 降低监视频率到0.5秒
            
        takeoff_in_progress = False
        print("[TAKEOFF] 监视结束 (当前高度 %.2f)" % safe_alt())

    th = threading.Thread(target=watcher)
    th.daemon = True
    th.start()

# ========== WebSocket / HTTP 处理 ==========

class ControlWS(tornado.websocket.WebSocketHandler):
    def open(self):
        control_clients.add(self)
        log("Control client connected (%d)" % len(control_clients))

    def on_message(self, message):
        global last_velocity_cmd, last_joystick_time
        try:
            data = json.loads(message)
        except Exception as e:
            self.write_message(json.dumps({"type":"error","msg":"invalid json","detail":str(e)}))
            return

        typ = data.get("type")

        # 摇杆速度
        if typ == "joystick":
            # 检查当前模式
            current_mode = ""
            try:
                current_mode = vehicle.mode.name if vehicle else None
            except:
                pass
            
            # 如果在 LOITER 模式，拒绝操作
            if current_mode == "LOITER":
                log("[JOYSTICK] 检测到摇杆操作，但当前在 LOITER 模式，拒绝操作")
                self.write_message(json.dumps({
                    "type":"ack",
                    "cmd":"joystick",
                    "ok":False,
                    "msg":"当前在 LOITER 模式，请切换到 GUIDED 模式以启用摇杆控制"
                }))
                return
            
            # 正常处理摇杆数据（在可控模式下）
            vx = float(data.get("vx", 0.0))
            vy = float(data.get("vy", 0.0))
            vz = float(data.get("vz", 0.0))
            yaw_rate = float(data.get("yaw_rate", 0.0))
            last_velocity_cmd = {"vx": vx, "vy": vy, "vz": vz, "yaw_rate": yaw_rate}
            last_joystick_time = time.time()
            self.write_message(json.dumps({"type":"ack","cmd":"joystick","ok":True}))
            return

        # 切模式
        if typ == "mode":
            m = str(data.get("mode","")).upper()
            if not m:
                self.write_message(json.dumps({"type":"ack","cmd":"mode","ok":False,"msg":"空模式"}))
                return
            ok = ensure_mode(m)
            self.write_message(json.dumps({"type":"ack","cmd":"mode","requested":m,"ok":ok}))
            return

        # ARM
        if typ == "arm":
            ok = arm_vehicle()
            self.write_message(json.dumps({"type":"ack","cmd":"arm","ok":ok}))
            return

        # DISARM
        if typ == "disarm":
            ok = disarm_vehicle()
            self.write_message(json.dumps({"type":"ack","cmd":"disarm","ok":ok}))
            return

        # TAKEOFF
        if typ == "takeoff":
            alt = float(data.get("alt", DEFAULT_TAKEOFF_ALT))
            t = threading.Thread(target=do_takeoff, args=(alt,))
            t.daemon = True
            t.start()
            self.write_message(json.dumps({"type":"ack","cmd":"takeoff","alt":alt,"ok":True}))
            return

        # LAND
        if typ == "land":
            global landing_in_progress
            ok = ensure_mode("LAND")
            if ok:
                landing_in_progress = True
                log("[LAND] 开始降落过程")
            self.write_message(json.dumps({"type":"ack","cmd":"land","ok":ok}))
            return

        # BRAKE: 立即停止水平移动
        if typ == "brake":
            if not vehicle:
                self.write_message(json.dumps({"type":"ack","cmd":"brake","ok":False,"msg":"vehicle not connected"}))
                return
            
            if not vehicle.armed:
                self.write_message(json.dumps({"type":"ack","cmd":"brake","ok":False,"msg":"not armed"}))
                return
            
            try:
                mode_name = vehicle.mode.name
                if mode_name in ("GUIDED", "GUIDED_NOGPS", "POSHOLD"):
                    # 在可控模式下,发送速度归零
                    send_ned_velocity(0.0, 0.0, 0.0, 0.0)
                    # 清空摇杆命令
                    last_velocity_cmd = {"vx": 0.0, "vy": 0.0, "vz": 0.0, "yaw_rate": 0.0}
                    last_joystick_time = 0.0
                    log("[BRAKE] 速度已归零，保持在 %s 模式" % mode_name)
                    self.write_message(json.dumps({"type":"ack","cmd":"brake","ok":True}))
                elif mode_name == "LOITER":
                    # 在 LOITER 模式下，提醒用户
                    log("[BRAKE] 当前在 LOITER 模式，已自动悬停")
                    self.write_message(json.dumps({
                        "type":"ack",
                        "cmd":"brake",
                        "ok":True,
                        "msg":"当前在 LOITER 模式，已自动悬停。如需摇杆控制，请切换到 GUIDED 模式"
                    }))
                else:
                    # 其他模式,尝试切换到 LOITER
                    ok = ensure_mode("LOITER")
                    log("[BRAKE] 切换到 LOITER: %s" % ok)
                    self.write_message(json.dumps({"type":"ack","cmd":"brake","ok":ok,"msg":"switched to LOITER"}))
            except Exception as e:
                log("[BRAKE] 异常: %s" % str(e))
                self.write_message(json.dumps({"type":"ack","cmd":"brake","ok":False,"msg":str(e)}))
            return

        # 诊断(可选)
        if typ == "diag":
            st = get_basic_status()
            # 附加最近几条 statustext
            statetxt = list(recent_statustext)[-5:]
            st['recent_statustext'] = statetxt
            self.write_message(json.dumps({"type":"diag_reply","diag":st}))
            return

        self.write_message(json.dumps({"type":"ack","cmd":typ,"ok":False,"msg":"unknown type"}))

    def on_close(self):
        if self in control_clients:
            control_clients.remove(self)
        log("Control client disconnected (%d)" % len(control_clients))

    def check_origin(self, origin):
        return True


class TelemetryWS(tornado.websocket.WebSocketHandler):
    def open(self):
        telemetry_clients.add(self)
        log("Telemetry client connected (%d)" % len(telemetry_clients))

    def on_close(self):
        if self in telemetry_clients:
            telemetry_clients.remove(self)
        log("Telemetry client disconnected (%d)" % len(telemetry_clients))

    def check_origin(self, origin):
        return True


class StatusHandler(tornado.web.RequestHandler):
    def get(self):
        st = get_basic_status()
        st['ok'] = True
        self.set_header("Content-Type","application/json")
        self.write(json.dumps(st))


# ========== 循环线程: 发送速度 / 推送遥测 ==========
def control_loop():
    global last_velocity_cmd, landing_in_progress
    while True:
        try:
            if vehicle and vehicle.armed:
                # 起飞或降落过程中完全停止速度控制，避免冲突
                if takeoff_in_progress or landing_in_progress:
                    time.sleep(0.1)
                    continue
                    
                mode_name = ""
                try:
                    mode_name = vehicle.mode.name
                except:
                    pass
                if mode_name in ("GUIDED","GUIDED_NOGPS","BRAKE","POSHOLD"):
                    age = time.time() - last_joystick_time
                    cmd = last_velocity_cmd
                    if age > VELOCITY_TIMEOUT:
                        # 超时清零
                        send_ned_velocity(0.0, 0.0, 0.0, 0.0)
                    else:
                        send_ned_velocity(cmd["vx"], cmd["vy"], cmd["vz"], cmd["yaw_rate"])
                elif mode_name == "LAND":
                    # 降落过程中检测是否已降落完成
                    if safe_alt() <= 0.2:  # 高度小于0.2米认为降落完成
                        if landing_in_progress:
                            landing_in_progress = False
                            log("[LAND] 降落完成，重置降落标志")
            else:
                # 未解锁时也稍作等待，减少CPU占用
                time.sleep(0.1)
            time.sleep(1.0/CONTROL_HZ)
        except Exception as e:
            # 避免线程退出
            time.sleep(0.5)

def telemetry_loop():
    while True:
        try:
            if telemetry_clients and vehicle:
                st = get_basic_status()
                # 计算控制延迟
                cmd_age_ms = int((time.time() - last_joystick_time) * 1000) if last_joystick_time > 0 else -1
                pkt = {
                    "type": "telemetry",
                    "mode": st.get("mode"),
                    "armed": st.get("armed"),
                    "altitude": st.get("altitude"),
                    "groundspeed": st.get("groundspeed"),
                    "heading": st.get("heading"),
                    "attitude": st.get("attitude"),  # 添加姿态信息
                    "battery": st.get("battery"),
                    "takeoff_in_progress": st.get("takeoff_in_progress"),
                    "takeoff_target_alt": st.get("takeoff_target_alt"),
                    "cmd_age_ms": cmd_age_ms,
                    "timestamp": int(time.time()*1000)
                }
                msg = json.dumps(pkt)
                dead = []
                for c in telemetry_clients:
                    try:
                        c.write_message(msg)
                    except:
                        dead.append(c)
                for d in dead:
                    if d in telemetry_clients:
                        telemetry_clients.remove(d)
            time.sleep(TELEM_INTERVAL)
        except Exception:
            time.sleep(1.0)

# ========== 连接飞控 & 启动服务 ==========

def connect_vehicle():
    conn = os.environ.get("DRONE_CONN", "/dev/ttyUSB0")
    baud = int(os.environ.get("DRONE_BAUD", "921600"))
    if conn.startswith("udp:") or conn.startswith("tcp:"):
        print("[SERVER] Connecting to vehicle (blocking): %s" % conn)
        v = connect(conn, wait_ready=True, timeout=120)
    else:
        print("[SERVER] Connecting to vehicle (blocking): %s baud=%d" % (conn, baud))
        v = connect(conn, baud=baud, wait_ready=True, timeout=120)
    print("[SERVER] Connected. Firmware: %s Battery: %s" % (str(v.version), str(v.battery)))
    try:
        v.add_message_listener('STATUSTEXT', _statustext_listener)
        print("[SERVER] STATUSTEXT listener attached")
    except Exception as e:
        print("[SERVER] Failed attach STATUSTEXT listener: %s" % e)
    return v

def make_app():
    return tornado.web.Application([
        (r"/api/status", StatusHandler),
        (r"/ws/control", ControlWS),
        (r"/ws/telemetry", TelemetryWS),
    ])

def main():
    global vehicle
    vehicle = connect_vehicle()

    # 启动后台线程
    ct = threading.Thread(target=control_loop)
    ct.daemon = True
    ct.start()
    tt = threading.Thread(target=telemetry_loop)
    tt.daemon = True
    tt.start()

    app = make_app()
    port = int(os.environ.get("DRONE_SERVER_PORT","8000"))
    app.listen(port, address="0.0.0.0")
    log("Server started on 0.0.0.0:%d" % port)

    try:
        tornado.ioloop.IOLoop.current().start()
    except KeyboardInterrupt:
        print("\n[SERVER] KeyboardInterrupt, exiting...")
    finally:
        try:
            if vehicle:
                vehicle.close()
        except:
            pass

if __name__ == "__main__":
    main()
