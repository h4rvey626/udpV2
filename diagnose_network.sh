#!/bin/bash

echo "====================================="
echo "  网络连接诊断工具"
echo "====================================="
echo ""

# 1. 检查 mjpg_streamer 是否运行
echo "[1] 检查 mjpg_streamer 进程:"
if pgrep -f mjpg_streamer > /dev/null; then
    echo "  ✓ mjpg_streamer 正在运行"
    ps aux | grep mjpg_streamer | grep -v grep
else
    echo "  ✗ mjpg_streamer 未运行!"
fi
echo ""

# 2. 检查端口 8080 是否监听
echo "[2] 检查端口 8080:"
if sudo ss -tulpn | grep :8080 > /dev/null; then
    echo "  ✓ 端口 8080 正在监听"
    sudo ss -tulpn | grep :8080
else
    echo "  ✗ 端口 8080 未监听!"
fi
echo ""

# 3. 显示所有网络接口的 IP 地址
echo "[3] 网络接口 IP 地址:"
ip addr show | grep -E "inet " | grep -v "127.0.0.1"
echo ""

# 4. 测试本地访问
echo "[4] 测试本地访问 (localhost):"
if curl -I -s http://localhost:8080/?action=stream | head -n 1; then
    echo "  ✓ 本地访问成功"
else
    echo "  ✗ 本地访问失败"
fi
echo ""

# 5. 获取主 IP 并测试
MAIN_IP=$(hostname -I | awk '{print $1}')
echo "[5] 测试主 IP 访问 ($MAIN_IP):"
if curl -I -s http://$MAIN_IP:8080/?action=stream | head -n 1; then
    echo "  ✓ 主 IP 访问成功"
else
    echo "  ✗ 主 IP 访问失败"
fi
echo ""

# 6. 检查防火墙状态
echo "[6] 防火墙状态:"
if command -v ufw &> /dev/null; then
    sudo ufw status
elif command -v firewall-cmd &> /dev/null; then
    sudo firewall-cmd --list-all
else
    echo "  未检测到常见防火墙工具"
fi
echo ""

# 7. 显示可用的连接 URL
echo "====================================="
echo "  可用的连接 URL:"
echo "====================================="
for ip in $(hostname -I); do
    echo "  http://$ip:8080/?action=stream"
done
echo ""

echo "建议:"
echo "1. 在手机浏览器中测试以上 URL"
echo "2. 确保手机和树莓派在同一网络"
echo "3. 如果防火墙启用,运行: sudo ufw allow 8080"
echo ""
