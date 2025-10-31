package com.example.controller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * H.264/RTP 低延迟视频流控件 (生产级优化版)
 * 适配树莓派 GStreamer: 320x240 @ 20fps H.264 RTP over UDP
 * 
 * @author h4rvey626
 * @version 2.0 (2025-10-28)
 */
public class CameraStreamView extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = "CameraStreamView";
    
    // ========== 配置参数 ==========
    private static final int VIDEO_WIDTH = 320;
    private static final int VIDEO_HEIGHT = 240;
    private static final int UDP_PORT = 5000;
    private static final int MAX_PACKET_SIZE = 2048;
    private static final int RTP_HEADER_SIZE = 12;
    
    // ⭐ 低延迟配置
    private static final int NALU_QUEUE_SIZE = 3;  // 3 帧缓冲 (150ms @ 20fps)
    private static final int I_FRAME_TIMEOUT_MS = 5000; // I 帧超时
    private static final int DECODER_TIMEOUT_US = 10000; // 10ms
    
    // ========== 状态标志 ==========
    private volatile boolean isStreaming = false;
    private volatile boolean decoderConfigured = false;
    private volatile boolean needReconfigure = false;
    
    private String serverIp = null;
    
    // ========== 线程和组件 ==========
    private Thread receiveThread;
    private Thread decodeThread;
    private DatagramSocket udpSocket;
    private MediaCodec decoder;
    private Surface decodeSurface;
    
    // ========== 数据结构 ==========
    private final BlockingQueue<byte[]> naluQueue = new LinkedBlockingQueue<>(NALU_QUEUE_SIZE);
    private final ByteBuffer rtpBuffer = ByteBuffer.allocate(200000); // 200KB
    
    // ========== RTP 状态 ==========
    private int lastSequence = -1;
    private boolean isAssemblingFUA = false;
    private int currentFUAType = -1;
    
    // ========== SPS/PPS 缓存 ==========
    private byte[] sps = null;
    private byte[] pps = null;
    private final Object codecLock = new Object();
    
    // ========== 性能统计 ==========
    private long totalBytes = 0;
    private long lastStatsTime = System.currentTimeMillis();
    private int decodedFrames = 0;
    private int droppedPackets = 0;
    private long lastIFrameTime = System.currentTimeMillis();
    
    // ========== UI ==========
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private volatile String statusMessage = "未连接";

    // ========== 构造函数 ==========
    
    public CameraStreamView(Context context) {
        this(context, null);
    }

    public CameraStreamView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CameraStreamView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        getHolder().addCallback(this);
        
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(40f);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setShadowLayer(5f, 2f, 2f, Color.BLACK);
    }

    // ========== 公共接口 ==========
    
    /**
     * 设置树莓派 IP 地址
     * @param ip 例如: "192.168.1.100"
     */
    public void setStreamUrl(String ip) {
        this.serverIp = ip;
        if (decodeSurface != null) {
            startStream();
        }
    }

    /**
     * 开始接收流
     */
    public void startStream() {
        if (isStreaming || decodeSurface == null || serverIp == null) {
            Log.w(TAG, String.format("启动条件不满足: streaming=%b, surface=%b, ip=%s", 
                isStreaming, (decodeSurface != null), serverIp));
            return;
        }
        
        isStreaming = true;
        statusMessage = "连接中...";
        postInvalidate();
        
        Log.i(TAG, String.format("🚀 启动 H.264/RTP 流: %s:%d (320x240@20fps)", serverIp, UDP_PORT));
        
        receiveThread = new Thread(this::receiveLoop, "RTP-Receiver");
        decodeThread = new Thread(this::decodeLoop, "H264-Decoder");
        receiveThread.setPriority(Thread.MAX_PRIORITY);
        decodeThread.setPriority(Thread.MAX_PRIORITY);
        receiveThread.start();
        decodeThread.start();
    }

    /**
     * 停止接收流
     */
    public void stopStream() {
        if (!isStreaming) return;
        
        isStreaming = false;
        statusMessage = "已停止";
        Log.i(TAG, "⏹️ 停止 H.264 流");
        
        // 中断线程
        if (receiveThread != null) receiveThread.interrupt();
        if (decodeThread != null) decodeThread.interrupt();
        
        // 清理资源
        synchronized (codecLock) {
            releaseDecoder();
        }
        
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
        
        naluQueue.clear();
        rtpBuffer.clear();
        
        postInvalidate();
    }

    // ========== Surface 回调 ==========
    
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "Surface 已创建");
        decodeSurface = holder.getSurface();
        if (serverIp != null && !serverIp.isEmpty()) {
            startStream();
        }
    }
    
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, String.format("Surface 尺寸: %dx%d", width, height));
    }
    
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "Surface 已销毁");
        stopStream();
        decodeSurface = null;
    }

    // ========== 解码器管理 ==========
    
    /**
     * 初始化 MediaCodec 解码器
     */
    private boolean initDecoder() {
        synchronized (codecLock) {
            try {
                // 验证 SPS/PPS
                if (sps == null || pps == null) {
                    Log.w(TAG, "SPS/PPS 未就绪，无法初始化解码器");
                    return false;
                }
                
                // 释放旧解码器
                releaseDecoder();
                
                Log.i(TAG, String.format("初始化解码器: SPS=%d bytes, PPS=%d bytes", sps.length, pps.length));
                
                // 创建 H.264 解码器
                decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                
                // 配置解码格式
                MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, 
                    VIDEO_WIDTH, 
                    VIDEO_HEIGHT
                );
                
                // ⭐ 关键配置
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 150000);
                format.setInteger(MediaFormat.KEY_PRIORITY, 0); // 最高优先级
                
                // 设置 SPS/PPS (CSD - Codec Specific Data)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
                format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
                
                // ⭐ 厂商特定低延迟优化 (可选)
                try {
                    format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1);
                } catch (Exception e) {
                    Log.d(TAG, "厂商扩展不支持: " + e.getMessage());
                }
                
                // 配置并启动解码器
                decoder.configure(format, decodeSurface, null, 0);
                decoder.start();
                
                decoderConfigured = true;
                needReconfigure = false;
                statusMessage = "播放中";
                postInvalidate();
                
                Log.i(TAG, "✅ 解码器已启动 (320x240 @ 20fps)");
                return true;
                
            } catch (IOException e) {
                Log.e(TAG, "❌ 解码器初始化失败", e);
                statusMessage = "解码器错误";
                postInvalidate();
                return false;
            }
        }
    }
    
    /**
     * 释放解码器资源
     */
    private void releaseDecoder() {
        if (decoder != null) {
            try {
                decoder.stop();
                decoder.release();
                Log.d(TAG, "解码器已释放");
            } catch (Exception e) {
                Log.e(TAG, "释放解码器失败", e);
            }
            decoder = null;
        }
        decoderConfigured = false;
    }

    // ========== RTP 接收线程 ==========
    
    private void receiveLoop() {
        try {
            // 绑定 UDP Socket
            udpSocket = new DatagramSocket(null);
            udpSocket.setReuseAddress(true);
            udpSocket.bind(new InetSocketAddress(UDP_PORT));
            udpSocket.setReceiveBufferSize(500000); // 500KB 缓冲
            udpSocket.setSoTimeout(100); // 100ms 超时，用于检查停止标志
            
            byte[] buffer = new byte[MAX_PACKET_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            
            Log.i(TAG, "📡 UDP 监听: 0.0.0.0:" + UDP_PORT);
            statusMessage = "等待数据...";
            postInvalidate();
            
            int consecutiveErrors = 0;
            
            while (isStreaming) {
                try {
                    udpSocket.receive(packet);
                    consecutiveErrors = 0; // 重置错误计数
                    
                    totalBytes += packet.getLength();
                    
                    // 处理 RTP 包
                    processRtpPacket(packet.getData(), packet.getLength());
                    
                    printStats();
                    
                } catch (java.net.SocketTimeoutException e) {
                    // 超时是正常的，用于检查 isStreaming 标志
                    continue;
                } catch (Exception e) {
                    consecutiveErrors++;
                    if (consecutiveErrors > 10) {
                        Log.e(TAG, "连续 10 次接收错误，退出接收循环", e);
                        break;
                    }
                    Log.w(TAG, "接收异常 #" + consecutiveErrors, e);
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "❌ UDP 接收错误", e);
            statusMessage = "网络错误";
            postInvalidate();
        } finally {
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
                Log.d(TAG, "UDP Socket 已关闭");
            }
        }
    }
    
    /**
     * 处理单个 RTP 包
     */
    private void processRtpPacket(byte[] data, int length) {
        if (length < RTP_HEADER_SIZE) {
            droppedPackets++;
            return;
        }
        
        // ========== 解析 RTP Header (RFC 3550) ==========
        
        int version = (data[0] >> 6) & 0x03;
        boolean padding = (data[0] & 0x20) != 0;
        boolean hasExtension = (data[0] & 0x10) != 0;
        int csrcCount = data[0] & 0x0F;
        boolean marker = (data[1] & 0x80) != 0;
        int payloadType = data[1] & 0x7F;
        int sequence = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        
        // 检测丢包
        if (lastSequence != -1) {
            int expectedSeq = (lastSequence + 1) & 0xFFFF;
            if (sequence != expectedSeq) {
                int lost = (sequence - expectedSeq) & 0xFFFF;
                Log.w(TAG, String.format("⚠️ 丢包 %d 个 (期望 %d, 收到 %d)", lost, expectedSeq, sequence));
                droppedPackets += lost;
                
                // 重置 FU-A 组装状态
                rtpBuffer.clear();
                isAssemblingFUA = false;
            }
        }
        lastSequence = sequence;
        
        // 计算 Payload 偏移
        int headerSize = RTP_HEADER_SIZE + (csrcCount * 4);
        if (hasExtension && length > headerSize + 4) {
            int extLen = ((data[headerSize + 2] & 0xFF) << 8) | (data[headerSize + 3] & 0xFF);
            headerSize += 4 + (extLen * 4);
        }
        
        if (padding && length > headerSize) {
            int paddingLen = data[length - 1] & 0xFF;
            length -= paddingLen;
        }
        
        if (headerSize >= length) {
            droppedPackets++;
            return;
        }
        
        int payloadSize = length - headerSize;
        
        // ========== 处理 H.264 Payload (RFC 6184) ==========
        
        int nalUnitType = data[headerSize] & 0x1F;
        
        if (nalUnitType == 28) {
            // FU-A 分片包
            processFUAPacket(data, headerSize, payloadSize);
        } else if (nalUnitType >= 1 && nalUnitType <= 23) {
            // 单个 NAL 单元
            processSingleNALU(data, headerSize, payloadSize);
        } else {
            Log.w(TAG, "不支持的 NAL 类型: " + nalUnitType);
        }
    }
    
    /**
     * 处理 FU-A 分片包
     */
    private void processFUAPacket(byte[] data, int offset, int length) {
        if (length < 2) return;
        
        byte fuIndicator = data[offset];
        byte fuHeader = data[offset + 1];
        
        boolean isStart = (fuHeader & 0x80) != 0;
        boolean isEnd = (fuHeader & 0x40) != 0;
        int nalType = fuHeader & 0x1F;
        
        if (isStart) {
            // FU-A 开始
            rtpBuffer.clear();
            
            // 添加起始码
            rtpBuffer.put(new byte[]{0x00, 0x00, 0x00, 0x01});
            
            // 重建 NAL Header
            byte nalHeader = (byte) ((fuIndicator & 0xE0) | nalType);
            rtpBuffer.put(nalHeader);
            
            // 添加 Payload
            if (rtpBuffer.remaining() >= length - 2) {
                rtpBuffer.put(data, offset + 2, length - 2);
                isAssemblingFUA = true;
                currentFUAType = nalType;
            } else {
                Log.w(TAG, "FU-A 起始片段过大，丢弃");
                rtpBuffer.clear();
                isAssemblingFUA = false;
            }
            
        } else if (isAssemblingFUA && currentFUAType == nalType) {
            // FU-A 中间或结束片段
            if (rtpBuffer.remaining() >= length - 2) {
                rtpBuffer.put(data, offset + 2, length - 2);
            } else {
                Log.w(TAG, "FU-A 缓冲区溢出，丢弃整个 NALU");
                rtpBuffer.clear();
                isAssemblingFUA = false;
                return;
            }
            
            if (isEnd) {
                // FU-A 结束，提取完整 NALU
                byte[] nalu = new byte[rtpBuffer.position()];
                rtpBuffer.flip();
                rtpBuffer.get(nalu);
                rtpBuffer.clear();
                isAssemblingFUA = false;
                
                enqueueNALU(nalu);
            }
        } else {
            // 状态不匹配，重置
            if (isAssemblingFUA) {
                Log.w(TAG, "FU-A 序列中断，重置缓冲区");
            }
            rtpBuffer.clear();
            isAssemblingFUA = false;
        }
    }
    
    /**
     * 处理单个 NAL 单元
     */
    private void processSingleNALU(byte[] data, int offset, int length) {
        // 创建带起始码的 NALU
        byte[] nalu = new byte[length + 4];
        nalu[0] = 0x00;
        nalu[1] = 0x00;
        nalu[2] = 0x00;
        nalu[3] = 0x01;
        System.arraycopy(data, offset, nalu, 4, length);
        
        enqueueNALU(nalu);
    }
    
    /**
     * 将 NALU 加入解码队列
     */
    private void enqueueNALU(byte[] nalu) {
        if (nalu == null || nalu.length < 5) return;
        
        int nalType = nalu[4] & 0x1F;
        
        // ========== 处理 SPS (7) ==========
        if (nalType == 7) {
            synchronized (codecLock) {
                byte[] newSps = Arrays.copyOf(nalu, nalu.length);
                if (sps == null || !Arrays.equals(sps, newSps)) {
                    Log.i(TAG, "📝 收到 SPS 参数集 (" + nalu.length + " bytes)");
                    sps = newSps;
                    
                    if (decoderConfigured) {
                        Log.w(TAG, "SPS 参数变化，需要重新配置解码器");
                        needReconfigure = true;
                    }
                }
            }
            return; // SPS 不送入解码器
        }
        
        // ========== 处理 PPS (8) ==========
        if (nalType == 8) {
            synchronized (codecLock) {
                byte[] newPps = Arrays.copyOf(nalu, nalu.length);
                if (pps == null || !Arrays.equals(pps, newPps)) {
                    Log.i(TAG, "📝 收到 PPS 参数集 (" + nalu.length + " bytes)");
                    pps = newPps;
                    
                    if (decoderConfigured) {
                        Log.w(TAG, "PPS 参数变化，需要重新配置解码器");
                        needReconfigure = true;
                    }
                }
            }
            return; // PPS 不送入解码器
        }
        
        // ========== 检测 I 帧 (IDR - 5) ==========
        if (nalType == 5) {
            lastIFrameTime = System.currentTimeMillis();
            Log.d(TAG, "🔑 收到 I 帧 (IDR)");
        }
        
        // ========== 加入解码队列 ==========
        if (!naluQueue.offer(nalu)) {
            // 队列满，移除最旧的帧
            naluQueue.poll();
            naluQueue.offer(nalu);
            Log.d(TAG, "队列满，丢弃旧帧");
        }
    }

    // ========== 解码线程 ==========
    
    private void decodeLoop() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int iFrameWarningCount = 0;
        
        // ⭐ 等待 SPS/PPS
        Log.d(TAG, "等待 SPS/PPS 参数集...");
        while (isStreaming && (sps == null || pps == null)) {
            try {
                Thread.sleep(500);
                if (System.currentTimeMillis() - lastStatsTime > 3000) {
                    Log.w(TAG, "⚠️ 等待 SPS/PPS 超过 3 秒，请检查 GStreamer 是否正常推流");
                    statusMessage = "等待配置...";
                    postInvalidate();
                }
            } catch (InterruptedException e) {
                return;
            }
        }
        
        if (!isStreaming) {
            Log.w(TAG, "流已停止，解码线程退出");
            return;
        }
        
        // ⭐ 初始化解码器
        if (!initDecoder()) {
            Log.e(TAG, "❌ 解码器初始化失败，解码线程退出");
            statusMessage = "解码器错误";
            postInvalidate();
            return;
        }
        
        Log.i(TAG, "🎬 解码循环已启动");
        
        // ⭐ 主解码循环
        while (isStreaming) {
            try {
                // 检查是否需要重新配置解码器
                if (needReconfigure) {
                    Log.i(TAG, "重新配置解码器...");
                    synchronized (codecLock) {
                        if (!initDecoder()) {
                            Log.e(TAG, "重新配置失败");
                            break;
                        }
                    }
                }
                
                // ⭐ 检测 I 帧超时
                long timeSinceLastIFrame = System.currentTimeMillis() - lastIFrameTime;
                if (timeSinceLastIFrame > I_FRAME_TIMEOUT_MS) {
                    iFrameWarningCount++;
                    if (iFrameWarningCount % 10 == 1) {
                        Log.w(TAG, String.format("⚠️ %d 秒未收到 I 帧，清空队列", timeSinceLastIFrame / 1000));
                    }
                    naluQueue.clear();
                    continue;
                } else {
                    iFrameWarningCount = 0;
                }
                
                // 获取 NALU
                byte[] nalu = naluQueue.poll(200, TimeUnit.MILLISECONDS);
                if (nalu == null) {
                    continue;
                }
                
                // ⭐ 送入解码器
                synchronized (codecLock) {
                    if (decoder == null || !decoderConfigured) {
                        continue;
                    }
                    
                    int inputIndex = decoder.dequeueInputBuffer(DECODER_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                        inputBuffer.clear();
                        inputBuffer.put(nalu);
                        
                        decoder.queueInputBuffer(
                            inputIndex, 
                            0, 
                            nalu.length, 
                            System.nanoTime() / 1000, 
                            0
                        );
                    }
                    
                    // ⭐ 获取解码输出（渲染到 Surface）
                    int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0);
                    
                    if (outputIndex >= 0) {
                        decoder.releaseOutputBuffer(outputIndex, true); // 直接渲染
                        decodedFrames++;
                        
                    } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat newFormat = decoder.getOutputFormat();
                        Log.i(TAG, "输出格式变化: " + newFormat);
                    }
                }
                
            } catch (InterruptedException e) {
                Log.d(TAG, "解码线程被中断");
                break;
            } catch (Exception e) {
                Log.e(TAG, "解码异常", e);
                if (isStreaming) {
                    // 尝试恢复
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        }
        
        Log.i(TAG, "解码循环已退出");
    }

    // ========== 性能统计 ==========
    
    private void printStats() {
        long now = System.currentTimeMillis();
        if (now - lastStatsTime >= 1000) {
            float kbps = totalBytes / 1024f;
            Log.i(TAG, String.format("📊 %.1f KB/s | %d fps | 丢包: %d | 队列: %d", 
                kbps, decodedFrames, droppedPackets, naluQueue.size()));
            
            totalBytes = 0;
            decodedFrames = 0;
            lastStatsTime = now;
        }
    }

    // ========== UI 绘制 ==========
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // 只在非播放状态显示状态文本
        if (!decoderConfigured || !isStreaming) {
            canvas.drawColor(Color.BLACK);
            canvas.drawText(statusMessage, 30, 80, textPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopStream();
    }
}