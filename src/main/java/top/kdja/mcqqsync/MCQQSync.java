package top.kdja.mcqqsync;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

public class MCQQSync extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private WebSocket webSocket;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "MCQQSync-WS"));
    private HttpClient httpClient;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        httpClient = HttpClient.newHttpClient();
        connectWebSocket();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("mcqqsync")).setExecutor(this);
        Objects.requireNonNull(getCommand("mcqqsync")).setTabCompleter(this);  // 添加命令补全
        getLogger().info("MCQQSync enabled");
    }

    @Override
    public void onDisable() {
        try {
            if (webSocket != null) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Plugin disabled").join();
            }
        } catch (Exception ignored) {}
        executor.shutdownNow();
        getLogger().info("MCQQSync disabled");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("mcqqsync")) {
            return false;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "MCQQSync - 子命令: reload (重载配置), reconnect (强制重连)");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(ChatColor.GREEN + "配置已重载");
            return true;
        } else if (args[0].equalsIgnoreCase("reconnect")) {
            if (webSocket != null) {
                try {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Force Reconnect").join();
                } catch (Exception e) {
                    getLogger().warning("关闭 WebSocket 时出错: " + e.getMessage());
                }
                webSocket = null;
            }
            connectWebSocket();
            sender.sendMessage(ChatColor.GREEN + "WebSocket 正在强制重连...");
            return true;
        } else {
            sender.sendMessage(ChatColor.RED + "未知子命令。使用: /mcqqsync <reload|reconnect>");
            return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("mcqqsync")) {
            return null;
        }

        if (args.length == 1) {
            // 补全子命令
            List<String> completions = Arrays.asList("reload", "reconnect");
            return completions.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(java.util.stream.Collectors.toList());
        }
        return Collections.emptyList();  // 无更多补全
    }

    private void connectWebSocket() {
        final String wsUrl = getConfig().getString("websocket_url", "ws://127.0.0.1:8765");
        executor.submit(() -> {
            try {
                webSocket = httpClient.newWebSocketBuilder()
                        .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                            @Override
                            public void onOpen(WebSocket webSocket) {
                                getLogger().info("MCQQSync 已连接到 " + wsUrl);
                                webSocket.request(1);
                            }

                            @Override
                            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                                getLogger().info("[MCQQSync] recv: " + data);
                                webSocket.request(1);
                                return CompletableFuture.completedFuture(null);
                            }

                            @Override
                            public void onError(WebSocket webSocket, Throwable error) {
                                getLogger().warning("MCQQSync WebSocket 错误: " + error);
                            }

                            @Override
                            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                                getLogger().info("MCQQSync WebSocket 连接关闭: " + statusCode + " " + reason);
                                // 简单重连机制：延后重连
                                executor.schedule(this::tryReconnect, 5, TimeUnit.SECONDS);
                                return CompletableFuture.completedFuture(null);
                            }

                            // helper to call from onClose
                            private void tryReconnect() {
                                getLogger().info("MCQQSync 正尝试重连...");
                                connectWebSocket();
                            }
                        }).join();
            } catch (Exception e) {
                getLogger().warning("MCQQSync 连接失败: " + e.getMessage());
                // 简单重试
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                connectWebSocket();
            }
        });
    }

    private void sendJsonAsync(JsonObject obj) {
        if (webSocket == null) {
            getLogger().warning("WS 未连接; 丢包: " + obj.toString());
            return;
        }
        String txt = obj.toString();
        executor.submit(() -> {
            try {
                webSocket.sendText(txt, true).join();
            } catch (Exception e) {
                getLogger().warning("WS 数据发送失败: " + e.getMessage());
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "join");
        o.addProperty("player", e.getPlayer().getName());
        o.addProperty("uuid", e.getPlayer().getUniqueId().toString());
        o.addProperty("time", Instant.now().toEpochMilli());
        sendJsonAsync(o);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "quit");
        o.addProperty("player", e.getPlayer().getName());
        o.addProperty("uuid", e.getPlayer().getUniqueId().toString());
        o.addProperty("time", Instant.now().toEpochMilli());
        sendJsonAsync(o);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChat(AsyncPlayerChatEvent e) {
        if (e.isCancelled() && !getConfig().getBoolean("chat.send_cancelled", false)) return;
        String raw = ChatColor.stripColor(e.getMessage());
        JsonObject o = new JsonObject();
        o.addProperty("type", "chat");
        o.addProperty("player", e.getPlayer().getName());
        o.addProperty("uuid", e.getPlayer().getUniqueId().toString());
        o.addProperty("message", raw);
        o.addProperty("time", Instant.now().toEpochMilli());
        sendJsonAsync(o);
    }
}