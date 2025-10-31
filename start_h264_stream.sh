#!/bin/bash
# H.264/RTP 视频流启动脚本

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== H.264/RTP 视频流启动工具 ===${NC}"
echo ""

# 检查设备
if [ ! -e /dev/video0 ]; then
    echo -e "${RED}错误: 找不到摄像头设备 /dev/video0${NC}"
    exit 1
fi

# 获取 Android 设备 IP
echo -e "${YELLOW}请输入 Android 设备的 IP 地址:${NC}"
read -p "IP: " ANDROID_IP

if [ -z "$ANDROID_IP" ]; then
    echo -e "${RED}错误: IP 地址不能为空${NC}"
    exit 1
fi

# 选择配置方案
echo ""
echo "选择视频配置方案:"
echo "1) 推荐配置 (320x240 @ 20fps, 400kbps) - 平衡延迟和画质"
echo "2) 低码率配置 (320x240 @ 20fps, 200kbps) - 极限低带宽"
echo "3) 高清配置 (640x480 @ 20fps, 800kbps) - 高画质"
read -p "请选择 [1-3]: " choice

case $choice in
    1)
        echo -e "${GREEN}启动推荐配置...${NC}"
        gst-launch-1.0 v4l2src device=/dev/video0 io-mode=2 \
          ! video/x-raw,width=320,height=240,framerate=20/1 \
          ! v4l2h264enc \
              extra-controls="controls,video_bitrate=400000,h264_profile=1,h264_level=10,h264_i_frame_period=10;" \
          ! video/x-h264,stream-format=byte-stream \
          ! h264parse config-interval=1 \
          ! rtph264pay config-interval=1 pt=96 mtu=1400 \
          ! udpsink host=$ANDROID_IP port=5000 sync=false async=false
        ;;
    2)
        echo -e "${GREEN}启动低码率配置...${NC}"
        gst-launch-1.0 v4l2src device=/dev/video0 io-mode=2 \
          ! video/x-raw,width=320,height=240,framerate=20/1 \
          ! v4l2h264enc \
              extra-controls="controls,video_bitrate=200000,h264_profile=1,h264_level=10;" \
          ! h264parse config-interval=1 \
          ! rtph264pay config-interval=1 pt=96 \
          ! udpsink host=$ANDROID_IP port=5000 sync=false async=false
        ;;
    3)
        echo -e "${YELLOW}注意: 高清模式需要修改 Android 端代码中的分辨率参数${NC}"
        echo -e "${YELLOW}按 Enter 继续，或 Ctrl+C 取消...${NC}"
        read
        gst-launch-1.0 v4l2src device=/dev/video0 io-mode=2 \
          ! video/x-raw,width=640,height=480,framerate=20/1 \
          ! v4l2h264enc \
              extra-controls="controls,video_bitrate=800000,h264_profile=1,h264_level=11;" \
          ! h264parse config-interval=1 \
          ! rtph264pay config-interval=1 pt=96 \
          ! udpsink host=$ANDROID_IP port=5000 sync=false async=false
        ;;
    *)
        echo -e "${RED}无效选择${NC}"
        exit 1
        ;;
esac
