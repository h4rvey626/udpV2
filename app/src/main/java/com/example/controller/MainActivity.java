package com.example.controller;

import static android.widget.Toast.LENGTH_SHORT;

import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.widget.Toast;

import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONException;
import org.json.JSONObject;


public class MainActivity extends AppCompatActivity {

    private FrameLayout joystickRight;
    private View joystickRightThumb;
    private Button btnGuided, btnLand, btnBrake, btnConnect, btnTakeoff;
    private Button btnArm;
    private TextView textAltitude, textSpeed, textHeading, textPosition, textAttitude, textMode;
    private CameraStreamView cameraStreamView;

    // 左摇杆十字按钮
    private Button btnUp, btnDown, btnLeft, btnRight;

    // 摇杆输入归一化 (-1~1) - 添加 volatile 保证线程安全
    private volatile float lastThrottleNorm = 0f;
    private volatile float lastYawNorm = 0f;
    private volatile float lastPitchNorm = 0f;
    private volatile float lastRollNorm = 0f;

    // 当前模式（来自遥测）
    private boolean isArmed = false;

    private Handler leftJoystickHandler = new Handler();
    private static final int REPEAT_INTERVAL_MS = 100;

    // 连接状态
    private boolean isConnected = false;

    // ===== 网络部分 ====
    private NetworkClient networkClient;
    private Handler sendHandler = new Handler();

    // ⭐ 优化：提高到 20Hz（提高控制响应速度）
    private final int SEND_INTERVAL = 50; // 20Hz

    private final float MAX_H_SPEED = 1.0f;
    private final float MAX_CLIMB = 1.0f;
    private final float MAX_YAW_RATE = (float)Math.toRadians(45);

    // ======= 遥测数据缓存 =======
    private double lastTelemAltitude = 0;
    private Double lastTelemHeading = null;
    private Double lastTelemGroundspeed = null;
    private boolean lastTelemArmed = false;
    private String lastTelemMode = "UNKNOWN";
    // 姿态数据缓存
    private Double lastTelemRoll = null;
    private Double lastTelemPitch = null;
    private Double lastTelemYaw = null;
    
    // ⭐ UI更新节流
    private long lastUiUpdateTime = 0;
    private static final int UI_UPDATE_INTERVAL = 100; // 10Hz UI更新

    // ⭐ 优化：摇杆几何参数缓存
    private float joystickCenterX = 0f;
    private float joystickCenterY = 0f;
    private float joystickMaxRadius = 0f;
    private boolean joystickInitialized = false;

    // ⭐ 发送循环（直接读取最新摇杆值）
    private final Runnable sendLoop = new Runnable() {
        @Override
        public void run() {
            if (isConnected && networkClient != null) {
                // 立即读取最新摇杆值并发送
                float vx = lastPitchNorm * MAX_H_SPEED;
                float vy = lastRollNorm * MAX_H_SPEED;
                float vz = -lastThrottleNorm * MAX_CLIMB;
                float yawRate = lastYawNorm * MAX_YAW_RATE;
                networkClient.sendJoystick(vx, vy, vz, yawRate);
            }
            sendHandler.postDelayed(this, SEND_INTERVAL);
        }
    };

    // ==== 左摇杆长按重复 ====
    private final Runnable upRepeatRunnable = new Runnable() {
        @Override public void run() {
            lastThrottleNorm = 1f;
            leftJoystickHandler.postDelayed(this, REPEAT_INTERVAL_MS);
        }
    };
    private final Runnable downRepeatRunnable = new Runnable() {
        @Override public void run() {
            lastThrottleNorm = -1f;
            leftJoystickHandler.postDelayed(this, REPEAT_INTERVAL_MS);
        }
    };
    private final Runnable leftRepeatRunnable = new Runnable() {
        @Override public void run() {
            lastYawNorm = -1f;
            leftJoystickHandler.postDelayed(this, REPEAT_INTERVAL_MS);
        }
    };
    private final Runnable rightRepeatRunnable = new Runnable() {
        @Override public void run() {
            lastYawNorm = 1f;
            leftJoystickHandler.postDelayed(this, REPEAT_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        bindViews();
        initUiLogic();
    }

    private void bindViews() {
        joystickRight = findViewById(R.id.joystickRightContainer);
        joystickRightThumb = findViewById(R.id.joystickRightThumb);
        textAltitude = findViewById(R.id.textAltitude);
        textSpeed = findViewById(R.id.textSpeed);
        textHeading = findViewById(R.id.textHeading);
        textPosition = findViewById(R.id.textPosition);
        textAttitude = findViewById(R.id.textAttitude);
        textMode = findViewById(R.id.textMode);
        cameraStreamView = findViewById(R.id.cameraStreamView);

        btnGuided = findViewById(R.id.btnGuided);
        btnTakeoff = findViewById(R.id.btnTAKEOFF);
        btnLand = findViewById(R.id.btnLand);
        btnBrake = findViewById(R.id.btnKill);
        btnConnect = findViewById(R.id.btnConnect);

        btnUp = findViewById(R.id.btnUp);
        btnDown = findViewById(R.id.btnDown);
        btnLeft = findViewById(R.id.btnLeft);
        btnRight = findViewById(R.id.btnRight);

        btnArm = findViewById(R.id.btnArm);
        updateArmButtonText();
        updateConnectButtonColor(false);
    }

    private void initUiLogic() {
        // 左侧方向按钮
        btnUp.setOnTouchListener((v,e)->handleRepeatTouch(e, upRepeatRunnable, true));
        btnDown.setOnTouchListener((v,e)->handleRepeatTouch(e, downRepeatRunnable, true));
        btnLeft.setOnTouchListener((v,e)->handleRepeatTouch(e, leftRepeatRunnable, false));
        btnRight.setOnTouchListener((v,e)->handleRepeatTouch(e, rightRepeatRunnable, false));

        // 右摇杆
        joystickRight.setOnTouchListener((v,event)->{
            handleRightJoystick(event);
            if (event.getAction()==MotionEvent.ACTION_UP || event.getAction()==MotionEvent.ACTION_CANCEL) {
                v.performClick();
            }
            return true;
        });

        btnGuided.setOnClickListener(v -> {
            if (networkClient != null) {
                networkClient.sendMode("GUIDED");
                Toast.makeText(this,"切换到 GUIDED 模式", LENGTH_SHORT).show();
            }
        });

        btnTakeoff.setOnClickListener(v -> {
            if (!isArmed) {
                Toast.makeText(this,"⚠️ 先 ARM", LENGTH_SHORT).show();
                return;
            }
            if (networkClient != null) {
                networkClient.sendTakeoff(1.5f);
                Toast.makeText(this,"发送 TAKEOFF 1.5m", LENGTH_SHORT).show();
            }
        });

        btnLand.setOnClickListener(v -> {
            if (networkClient != null) {
                networkClient.sendCommand("land");
                Toast.makeText(this,"发送 LAND", LENGTH_SHORT).show();
            }
        });

        btnBrake.setOnClickListener(v -> {
            if (networkClient != null) {
                networkClient.sendCommand("brake");
                Toast.makeText(this,"发送 BRAKE (刹车)", LENGTH_SHORT).show();
            }
        });

        btnConnect.setOnClickListener(v -> {
            if (isConnected) showDisconnectDialog();
            else showConnectDialog();
        });

        btnArm.setOnClickListener(v -> {
            if (isArmed) {
                sendArmDisarm(false);
            } else {
                sendArmDisarm(true);
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main),(view,insets)->{
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return insets;
        });
    }

    // ================= 遥测解析 =================
    private void handleTelemetry(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (!"telemetry".equals(o.optString("type"))) return;

            // 提取字段
            lastTelemAltitude = o.optDouble("altitude", 0.0);
            if (lastTelemAltitude < -5) {
                lastTelemAltitude = 0;
            }
            lastTelemHeading = o.has("heading") && !o.isNull("heading") ? o.optDouble("heading") : null;
            lastTelemGroundspeed = o.has("groundspeed") && !o.isNull("groundspeed") ? o.optDouble("groundspeed") : null;
            lastTelemArmed = o.optBoolean("armed", false);
            lastTelemMode = o.optString("mode", "UNKNOWN");
            
            // 提取姿态信息
            if (o.has("attitude") && !o.isNull("attitude")) {
                JSONObject attitude = o.getJSONObject("attitude");
                lastTelemRoll = attitude.has("roll") && !attitude.isNull("roll") ? attitude.optDouble("roll") : null;
                lastTelemPitch = attitude.has("pitch") && !attitude.isNull("pitch") ? attitude.optDouble("pitch") : null;
                lastTelemYaw = attitude.has("yaw") && !attitude.isNull("yaw") ? attitude.optDouble("yaw") : null;
            }

            // 提取控制状态信息
            if (o.has("control_status") && !o.isNull("control_status")) {
                JSONObject controlStatus = o.getJSONObject("control_status");
                boolean canControl = controlStatus.optBoolean("can_control", false);
                String currentMode = controlStatus.optString("current_mode", "UNKNOWN");
                String message = controlStatus.optString("message", "");
                
                // 如果控制状态发生变化，显示提醒
                updateControlStatus(canControl, currentMode, message);
            }

            // 同步内部状态
            isArmed = lastTelemArmed;

            // ⭐ 节流UI更新
            long now = System.currentTimeMillis();
            if (now - lastUiUpdateTime >= UI_UPDATE_INTERVAL) {
                lastUiUpdateTime = now;
                runOnUiThread(this::updateUiFromTelemetry);
            }
        } catch (JSONException e) {
            // 忽略解析错误
        }
    }

    private void updateUiFromTelemetry() {
        textAltitude.setText(String.format("高度: %.2f m", lastTelemAltitude));
        
        if (lastTelemGroundspeed != null) {
            textSpeed.setText(String.format("地速: %.2f m/s", lastTelemGroundspeed));
        } else {
            textSpeed.setText("地速: -");
        }
        
        if (lastTelemHeading != null) {
            textHeading.setText(String.format("航向: %.0f°", lastTelemHeading));
        } else {
            textHeading.setText("航向: -");
        }
        
        // 更新姿态信息
        if (lastTelemRoll != null && lastTelemPitch != null && lastTelemYaw != null) {
            // 将弧度转换为角度
            double rollDeg = Math.toDegrees(lastTelemRoll);
            double pitchDeg = Math.toDegrees(lastTelemPitch);
            double yawDeg = Math.toDegrees(lastTelemYaw);
            textAttitude.setText(String.format("姿态: R:%.1f° P:%.1f° Y:%.1f°", rollDeg, pitchDeg, yawDeg));
        } else {
            textAttitude.setText("姿态: -");
        }
        
        textPosition.setText("");
        textMode.setText("模式: " + lastTelemMode);
        updateArmButtonText();
    }

    // 控制状态更新
    private void updateControlStatus(boolean canControl, String currentMode, String message) {
        runOnUiThread(() -> {
            // 如果不能控制且消息不为空，显示提醒
            if (!canControl && !message.isEmpty()) {
                // 显示控制状态提醒
                Toast.makeText(this, "⚠️ " + message, Toast.LENGTH_LONG).show();
                
                // 更新模式显示为红色警告
                textMode.setTextColor(Color.RED);
                textMode.setText("模式: " + currentMode + " (摇杆不可用)");
            } else if (canControl) {
                // 恢复正常显示
                textMode.setTextColor(Color.WHITE);
                textMode.setText("模式: " + currentMode);
            }
        });
    }

    // ===== 连接管理 =====
    private void showConnectDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("连接到无人机服务器");
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_connect, null);
        TextInputEditText editIP = dialogView.findViewById(R.id.editIP);
        TextInputEditText editPort = dialogView.findViewById(R.id.editPort);
        editPort.setText("8000");
        builder.setView(dialogView);
        builder.setPositiveButton("连接",(d,which)->{
            String ip = editIP.getText()==null?"":editIP.getText().toString().trim();
            String portStr = editPort.getText()==null?"":editPort.getText().toString().trim();
            if (ip.isEmpty() || portStr.isEmpty()) {
                Toast.makeText(this,"⚠️ IP 或端口为空", LENGTH_SHORT).show();
                return;
            }
            int port;
            try { 
                port = Integer.parseInt(portStr); 
            } catch (Exception e) { 
                Toast.makeText(this,"端口无效", LENGTH_SHORT).show(); 
                return; 
            }
            initNetwork(ip, port);
        });
        builder.setNegativeButton("取消",null);
        builder.show();
    }

    private void initNetwork(String ip, int port) {
        networkClient = new NetworkClient();
        networkClient.setBase(ip, port);
        networkClient.setTelemetryListener(this::handleTelemetry);
        networkClient.getStatus((ok, body) -> runOnUiThread(() -> {
            if (ok) {
                isConnected = true;
                updateConnectButtonColor(true);
                Toast.makeText(this,"✅ 连接成功", LENGTH_SHORT).show();
                
                // 打开 WebSocket
                networkClient.openControlSocket();
                networkClient.openTelemetrySocket();
                
                // ⭐ 启动发送循环（30Hz）
                sendHandler.post(sendLoop);
                
                // 启动摄像头视频流（H.264/RTP UDP）
                // 注意：树莓派需要运行 GStreamer 推流到 UDP 端口 5000
                if (cameraStreamView != null) {
                    // 延迟 2 秒启动视频流（给树莓派启动时间）
                    new Handler().postDelayed(() -> {
                        cameraStreamView.setStreamUrl(ip); // 设置树莓派 IP
                        Toast.makeText(this,"📹 正在连接 H.264 视频流（UDP 5000）...", LENGTH_SHORT).show();
                    }, 2000);
                }
            } else {
                Toast.makeText(this,"❌ 连接失败: "+body, LENGTH_SHORT).show();
            }
        }));
    }

    private void showDisconnectDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("已连接");
        builder.setMessage("是否断开连接？");
        builder.setPositiveButton("断开",(d,w)->{
            isConnected = false;
            
            // 停止发送循环
            sendHandler.removeCallbacks(sendLoop);
            
            // 关闭网络连接
            if (networkClient != null) networkClient.close();
            
            // 停止视频流
            if (cameraStreamView != null) cameraStreamView.stopStream();
            
            updateConnectButtonColor(false);
            Toast.makeText(this,"🔗 已断开", LENGTH_SHORT).show();
        });
        builder.setNegativeButton("返回",null);
        builder.show();
    }

    // ===== 摇杆控制 =====
    private boolean handleRepeatTouch(MotionEvent e, Runnable r, boolean affectThrottle) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (!isArmed) {
                    Toast.makeText(this, "⚠️ 请先 ARM", LENGTH_SHORT).show();
                    return true;
                }
                leftJoystickHandler.post(r);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                leftJoystickHandler.removeCallbacks(r);
                if (affectThrottle) lastThrottleNorm = 0f;
                else lastYawNorm = 0f;
                return true;
        }
        return false;
    }

    // ⭐ 优化：右摇杆处理（缓存几何参数）
    private void handleRightJoystick(MotionEvent event) {
        // 首次初始化几何参数
        if (!joystickInitialized && joystickRight.getWidth() > 0) {
            int width = joystickRight.getWidth();
            int height = joystickRight.getHeight();
            joystickCenterX = width / 2f;
            joystickCenterY = height / 2f;
            joystickMaxRadius = Math.min(joystickCenterX, joystickCenterY) - (joystickRightThumb.getWidth()/2f);
            joystickInitialized = true;
        }

        // 未ARM时复位
        if (!isArmed) {
            if (joystickInitialized) {
                moveThumb(joystickRightThumb, joystickCenterX, joystickCenterY);
            }
            lastRollNorm = 0f;
            lastPitchNorm = 0f;
            return;
        }

        float dx = event.getX() - joystickCenterX;
        float dy = event.getY() - joystickCenterY;
        float dist = (float)Math.hypot(dx, dy);
        
        // 限制在圆形区域内
        if (dist > joystickMaxRadius) {
            float scale = joystickMaxRadius / dist;
            dx *= scale;
            dy *= scale;
        }

        // 归一化到 [-1, 1]
        lastRollNorm = dx / joystickMaxRadius;
        lastPitchNorm = -dy / joystickMaxRadius;

        // 处理松开事件
        if (event.getAction() == MotionEvent.ACTION_UP || 
            event.getAction() == MotionEvent.ACTION_CANCEL) {
            moveThumb(joystickRightThumb, joystickCenterX, joystickCenterY);
            lastRollNorm = 0f;
            lastPitchNorm = 0f;
        } else {
            moveThumb(joystickRightThumb, joystickCenterX + dx, joystickCenterY + dy);
        }
    }

    private void moveThumb(View thumb, float x, float y) {
        thumb.setX(x - thumb.getWidth()/2f);
        thumb.setY(y - thumb.getHeight()/2f);
    }

    // ===== 按钮控制 =====
    private void sendArmDisarm(boolean arm) {
        if (!isConnected || networkClient == null) {
            Toast.makeText(this,"⚠️ 未连接服务器", LENGTH_SHORT).show();
            return;
        }
        if (arm) {
            networkClient.sendCommand("arm");
            Toast.makeText(this,"🔓 发送 ARM", LENGTH_SHORT).show();
        } else {
            networkClient.sendCommand("disarm");
            Toast.makeText(this,"🔒 发送 DISARM", LENGTH_SHORT).show();
        }
    }

    private void updateArmButtonText() {
        if (btnArm != null) {
            btnArm.setText(isArmed ? "DISARM" : "ARM");
        }
    }

    private void updateConnectButtonColor(boolean connected) {
        if (connected) {
            btnConnect.setBackgroundTintList(ColorStateList.valueOf(Color.GREEN));
            btnConnect.setTextColor(Color.BLACK);
            btnConnect.setText("已连接");
        } else {
            btnConnect.setBackgroundTintList(ColorStateList.valueOf(Color.RED));
            btnConnect.setTextColor(Color.WHITE);
            btnConnect.setText("连接");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // 停止所有定时任务
        sendHandler.removeCallbacks(sendLoop);
        leftJoystickHandler.removeCallbacks(upRepeatRunnable);
        leftJoystickHandler.removeCallbacks(downRepeatRunnable);
        leftJoystickHandler.removeCallbacks(leftRepeatRunnable);
        leftJoystickHandler.removeCallbacks(rightRepeatRunnable);
        
        // 关闭网络和视频流
        if (networkClient != null) networkClient.close();
        if (cameraStreamView != null) cameraStreamView.stopStream();
    }
}