#!/bin/bash
#
# 检查无人机控制系统运行状态
#

echo "=== 无人机控制系统状态检查 ==="
echo ""

# 获取本机 IP
MY_IP=$(hostname -I | awk '{print $1}')
echo "📍 树莓派 IP: $MY_IP"
echo ""

# 检查 mjpg-streamer
echo "[1/3] 视频流服务器 (mjpg_streamer):"
if pgrep -x "mjpg_streamer" > /dev/null; then
    PID=$(pgrep -x "mjpg_streamer")
    echo "   ✅ 运行中 (PID: $PID)"
    
    # 检查端口
    if netstat -tuln 2>/dev/null | grep -q ":8080"; then
        echo "   ✅ 端口 8080 已监听"
        echo "   📹 视频地址: http://${MY_IP}:8080/?action=stream"
    else
        echo "   ⚠️  端口 8080 未监听"
    fi
    
    # 检查日志
    if [ -f /tmp/mjpg_streamer.log ]; then
        ERRORS=$(grep -i "error\|fail" /tmp/mjpg_streamer.log | tail -n 3)
        if [ -n "$ERRORS" ]; then
            echo "   ⚠️  发现错误日志:"
            echo "$ERRORS" | sed 's/^/      /'
        fi
    fi
else
    echo "   ❌ 未运行"
fi

echo ""

# 检查 drone_server
echo "[2/3] 控制服务器 (drone_server.py):"
if pgrep -f "drone_server.py" > /dev/null; then
    PID=$(pgrep -f "drone_server.py")
    echo "   ✅ 运行中 (PID: $PID)"
    
    # 检查端口
    if netstat -tuln 2>/dev/null | grep -q ":8000"; then
        echo "   ✅ 端口 8000 已监听"
        echo "   🎮 状态接口: http://${MY_IP}:8000/api/status"
    else
        echo "   ⚠️  端口 8000 未监听"
    fi
    
    # 检查日志
    if [ -f /tmp/drone_server.log ]; then
        ERRORS=$(grep -i "error\|exception\|fail" /tmp/drone_server.log | tail -n 3)
        if [ -n "$ERRORS" ]; then
            echo "   ⚠️  发现错误日志:"
            echo "$ERRORS" | sed 's/^/      /'
        fi
    fi
else
    echo "   ❌ 未运行"
fi

echo ""

# 检查摄像头设备
echo "[3/3] 硬件设备:"
if [ -e /dev/video0 ]; then
    echo "   ✅ 摄像头 /dev/video0 存在"
else
    echo "   ❌ 未找到摄像头设备"
fi

if [ -e /dev/ttyUSB0 ]; then
    echo "   ✅ 飞控设备 /dev/ttyUSB0 存在"
elif [ -e /dev/ttyACM0 ]; then
    echo "   ✅ 飞控设备 /dev/ttyACM0 存在"
else
    echo "   ⚠️  未找到飞控串口设备 (可能使用 UDP)"
fi

echo ""
echo "=== 状态检查完成 ==="
echo ""
echo "📚 查看完整日志:"
echo "   tail -f /tmp/mjpg_streamer.log"
echo "   tail -f /tmp/drone_server.log"
echo ""
