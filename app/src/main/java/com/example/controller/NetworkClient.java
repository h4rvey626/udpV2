package com.example.controller;

import android.util.Log;
import androidx.annotation.Nullable;

import com.google.gson.Gson;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class NetworkClient {

    public interface SimpleCallback {
        void onResult(boolean ok, String bodyOrErr);
    }

    public interface TelemetryListener {
        void onTelemetry(String json);
    }

    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    private String baseUrl = null;
    private WebSocket controlSocket;
    private WebSocket telemetrySocket;
    private TelemetryListener telemetryListener;

    public NetworkClient() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 长连接不超时
                .build();
    }

    public void setBase(String host, int port) {
        baseUrl = "http://" + host + ":" + port;
    }

    public void setTelemetryListener(TelemetryListener l) {
        telemetryListener = l;
    }

    public void getStatus(SimpleCallback cb) {
        if (baseUrl == null) {
            cb.onResult(false, "baseUrl == null");
            return;
        }
        Request r = new Request.Builder()
                .url(baseUrl + "/api/status")
                .get()
                .build();
        httpClient.newCall(r).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                cb.onResult(false, e.getMessage());
            }
            @Override public void onResponse(Call call, Response response) {
                try (ResponseBody b = response.body()) {
                    cb.onResult(response.isSuccessful(), b != null ? b.string() : "");
                } catch (Exception ex) {
                    cb.onResult(false, ex.getMessage());
                }
            }
        });
    }

    public void openControlSocket() {
        if (baseUrl == null) return;
        Request req = new Request.Builder()
                .url(baseUrl.replaceFirst("^http", "ws") + "/ws/control")
                .build();
        controlSocket = httpClient.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                Log.i("WS_CONTROL","opened");
            }
            @Override public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
                Log.e("WS_CONTROL","fail: " + t.getMessage());
            }
        });
    }

    public void openTelemetrySocket() {
        if (baseUrl == null) return;
        Request req = new Request.Builder()
                .url(baseUrl.replaceFirst("^http","ws") + "/ws/telemetry")
                .build();
        telemetrySocket = httpClient.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                Log.i("WS_TELEM","opened");
            }
            @Override public void onMessage(WebSocket webSocket, String text) {
                if (telemetryListener != null) telemetryListener.onTelemetry(text);
            }
            @Override public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
                Log.e("WS_TELEM","fail: " + t.getMessage());
            }
        });
    }

    public void sendJoystick(float vx, float vy, float vz, float yawRate) {
        if (controlSocket == null) return;
        JoystickMsg msg = new JoystickMsg(vx, vy, vz, yawRate);
        controlSocket.send(gson.toJson(msg));
    }

    public void sendMode(String mode) {
        if (controlSocket == null) return;
        ModeMsg msg = new ModeMsg(mode);
        controlSocket.send(gson.toJson(msg));
    }

    public void sendTakeoff(float alt) {
        if (controlSocket == null) return;
        TakeoffMsg msg = new TakeoffMsg(alt);
        controlSocket.send(gson.toJson(msg));
    }

    public void sendCommand(String type) {
        if (controlSocket == null) return;
        BaseMsg msg = new BaseMsg(type);
        controlSocket.send(gson.toJson(msg));
    }

    // 关闭所有 WebSocket
    public void close() {
        try {
            if (controlSocket != null) {
                controlSocket.close(1000, "bye");
                controlSocket = null;
            }
        } catch (Exception ignore) {}
        try {
            if (telemetrySocket != null) {
                telemetrySocket.close(1000, "bye");
                telemetrySocket = null;
            }
        } catch (Exception ignore) {}
        Log.i("NET","NetworkClient closed");
    }

    // ==== 内部消息结构 ====
    static class BaseMsg { final String type; BaseMsg(String t){ this.type = t; } }
    static class ModeMsg extends BaseMsg { final String mode; ModeMsg(String m){ super("mode"); this.mode = m; } }
    static class TakeoffMsg extends BaseMsg { final float alt; TakeoffMsg(float a){ super("takeoff"); this.alt = a; } }
    static class JoystickMsg extends BaseMsg {
        final float vx, vy, vz, yaw_rate;
        JoystickMsg(float vx,float vy,float vz,float yaw){
            super("joystick");
            this.vx=vx; this.vy=vy; this.vz=vz; this.yaw_rate=yaw;
        }
    }
}