package top.kdja.mcqqsync;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MCQQSync extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    final String version="1.0.1";
    private WebSocket webSocket;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "MCQQSync-WS"));
    private HttpClient httpClient;
    private String token;
    private File tokenFile;
    private final AtomicBoolean authSent = new AtomicBoolean(false);  // 确保 auth 只发一次 per 连接
    private boolean showAllLog = false;  // 详细日志开关

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // 设置默认配置
        getConfig().addDefault("websocket_url", "ws://127.0.0.1:8765");
        getConfig().addDefault("chat.send_cancelled", false);
        getConfig().addDefault("events.join", true);
        getConfig().addDefault("events.quit", true);
        getConfig().addDefault("events.chat", true);
        getConfig().addDefault("events.death", true);
        getConfig().addDefault("show_all_log", false);  // 新增：详细日志开关
        getConfig().options().copyDefaults(true);
        saveConfig();

        showAllLog = getConfig().getBoolean("show_all_log", false);  // 加载开关

        // 初始化 Token 文件
        getDataFolder().mkdirs();
        tokenFile = new File(getDataFolder(), "token");

        // 生成或加载 Token
        if (!tokenFile.exists() || (token = loadToken()) == null || token.isEmpty()) {
            token = generateToken();
            saveToken(token);
            getLogger().info("生成新的 Token: " + token);
        } else {
            getLogger().info("当前 Token: " + token);
        }

        httpClient = HttpClient.newHttpClient();
        connectWebSocket();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("mcqqsync")).setExecutor(this);
        Objects.requireNonNull(getCommand("mcqqsync")).setTabCompleter(this);
        getLogger().info("MCQQSync 已启用");
    }

    @Override
    public void onDisable() {
        try {
            if (webSocket != null) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "插件已禁用").join();
            }
        } catch (Exception ignored) {}
        executor.shutdownNow();
        getLogger().info("MCQQSync 已禁用");
    }

    private String loadToken() {
        try {
            if (!tokenFile.exists()) return null;
            byte[] encodedBytes = Files.readAllBytes(tokenFile.toPath());
            byte[] decodedBytes = Base64.getDecoder().decode(encodedBytes);
            return new String(decodedBytes);
        } catch (Exception e) {
            getLogger().warning("加载 Token 时出错: " + e.getMessage());
            return null;
        }
    }

    private void saveToken(String token) {
        try {
            byte[] tokenBytes = token.getBytes();
            byte[] encodedBytes = Base64.getEncoder().encode(tokenBytes);
            Files.write(tokenFile.toPath(), encodedBytes);
        } catch (Exception e) {
            getLogger().warning("保存 Token 时出错: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("mcqqsync")) {
            return false;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.AQUA + "Ciallo～(∠・ω< )⌒☆");
            sender.sendMessage(ChatColor.GREEN + "MCQQSync v"+version+" - By 卡带酱 - https://github.com/kdjnb/mcqqsync");
            sender.sendMessage(ChatColor.YELLOW + "MCQQSync - 子命令: reload (重载配置), reconnect (强制重连), token <get|reset> (Token管理，仅控制台)");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            showAllLog = getConfig().getBoolean("show_all_log", false);  // 重新加载开关
            sender.sendMessage(ChatColor.GREEN + "配置已重载（详细日志: " + (showAllLog ? "启用" : "禁用") + "）");
            return true;
        } else if (args[0].equalsIgnoreCase("reconnect")) {
            if (webSocket != null) {
                try {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "强制重连").join();
                } catch (Exception e) {
                    getLogger().warning("关闭 WebSocket 时出错: " + e.getMessage());
                }
                webSocket = null;
            }
            authSent.set(false);  // 重置 auth 标志
            connectWebSocket();
            sender.sendMessage(ChatColor.GREEN + "WebSocket 正在强制重连...");
            return true;
        } else if (args[0].equalsIgnoreCase("token")) {
            if (!(sender instanceof ConsoleCommandSender)) {
                sender.sendMessage(ChatColor.RED + "此命令只能在控制台执行");
                return true;
            }
            if (args.length == 2) {
                if ("get".equalsIgnoreCase(args[1])) {
                    sender.sendMessage(ChatColor.GREEN + "当前 Token: " + token);
                    return true;
                } else if ("reset".equalsIgnoreCase(args[1])) {
                    token = generateToken();
                    saveToken(token);
                    sender.sendMessage(ChatColor.GREEN + "新 Token 已生成: " + token);
                    getLogger().info("Token 已重置: " + token);
                    return true;
                }
            }
            sender.sendMessage(ChatColor.YELLOW + "使用: /mcqqsync token <get|reset>");
            return true;
        } else {
            sender.sendMessage(ChatColor.RED + "未知子命令。使用: /mcqqsync <reload|reconnect|token>");
            return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("mcqqsync")) {
            return null;
        }

        if (args.length == 1) {
            List<String> completions = Arrays.asList("reload", "reconnect", "token");
            return completions.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(java.util.stream.Collectors.toList());
        } else if (args.length == 2 && "token".equalsIgnoreCase(args[0])) {
            List<String> completions = Arrays.asList("get", "reset");
            return completions.stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .collect(java.util.stream.Collectors.toList());
        }
        return Collections.emptyList();
    }

    private String generateToken() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        Random rand = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(rand.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private void tryReconnect() {
        getLogger().info("MCQQSync 正在尝试重连...");
        authSent.set(false);  // 重置 auth 标志
        connectWebSocket();
    }

    private void connectWebSocket() {
        final String wsUrl = getConfig().getString("websocket_url", "ws://127.0.0.1:8765");
        if (showAllLog) getLogger().info("详细: 尝试连接到 " + wsUrl);
        executor.submit(() -> {
            try {
                webSocket = httpClient.newWebSocketBuilder()
                        .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                            @Override
                            public void onOpen(WebSocket webSocket) {
                                getLogger().info("MCQQSync 已连接到 " + wsUrl);
                                if (showAllLog) getLogger().info("详细: onOpen 回调触发，请求消息流");
                                webSocket.request(1);  // 只请求消息，不发送 auth
                            }

                            @Override
                            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                                getLogger().info("[MCQQSync] 收到: " + data);
                                if (showAllLog) getLogger().info("详细: 收到消息长度: " + data.length() + " | 最后分片: " + last);
                                webSocket.request(1);
                                return CompletableFuture.completedFuture(null);
                            }

                            @Override
                            public void onError(WebSocket webSocket, Throwable error) {
                                getLogger().warning("MCQQSync WebSocket 错误: " + error.getMessage());
                                if (showAllLog) {
                                    error.printStackTrace();  // 打印完整栈
                                    getLogger().info("详细: 错误类型: " + error.getClass().getSimpleName());
                                }
                            }

                            @Override
                            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                                getLogger().info("MCQQSync WebSocket 连接关闭: " + statusCode + " " + (reason != null ? " - 原因: " + reason : ""));
                                if (showAllLog) getLogger().info("详细: 关闭原因字符串: " + (reason != null ? reason : "null"));
                                executor.schedule(MCQQSync.this::tryReconnect, 10, TimeUnit.SECONDS);  // 延长重连间隔到 10s
                                return CompletableFuture.completedFuture(null);
                            }
                        }).join();

                // join() 完成后，延迟 500ms 发送 auth，给服务器缓冲
                if (!authSent.getAndSet(true)) {
                    executor.schedule(this::sendAuthIfNeeded, 500, TimeUnit.MILLISECONDS);
                }

            } catch (Exception e) {
                getLogger().warning("MCQQSync 连接失败: " + e.getMessage());
                if (showAllLog) {
                    e.printStackTrace();  // 打印完整栈
                    getLogger().info("详细: 连接异常类型: " + e.getClass().getSimpleName());
                }
                executor.schedule(this::tryReconnect, 10, TimeUnit.SECONDS);  // 失败时也延长时间
            }
        });
    }

    private void sendAuthIfNeeded() {
        if (webSocket == null) {
            getLogger().warning("尝试发送 auth 时 WS 已关闭");
            return;
        }
        JsonObject auth = new JsonObject();
        auth.addProperty("type", "auth");
        auth.addProperty("token", token);
        if (showAllLog) getLogger().info("详细: 准备发送 auth JSON: " + auth.toString());
        sendJsonAsync(auth);
        getLogger().info("已发送认证消息 (Token: " + token + ")");
    }

    private void sendJsonAsync(JsonObject obj) {
        if (webSocket == null) {
            getLogger().warning("WS 未连接; 丢包: " + obj.toString());
            if (showAllLog) getLogger().info("详细: 丢包原因: webSocket 为 null");
            return;
        }
        // 注意：auth 已手动添加 token，这里不重复添加（避免重复字段）
        if (!"auth".equals(obj.get("type").getAsString())) {
            obj.addProperty("token", token);
        }
        String txt = obj.toString();
        if (showAllLog) getLogger().info("详细: 准备发送 JSON: " + txt);
        executor.submit(() -> {
            try {
                webSocket.sendText(txt, true).join();
                if (showAllLog) getLogger().info("详细: 发送成功，消息: " + txt);
            } catch (Exception e) {
                getLogger().warning("WS 数据发送失败: " + e.getMessage() + " - 消息: " + txt);
                if (showAllLog) {
                    e.printStackTrace();  // 打印完整栈
                    getLogger().info("详细: 发送异常类型: " + e.getClass().getSimpleName());
                }
                // 发送失败时，不立即重连，让 onClose 处理
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        if (!getConfig().getBoolean("events.join", true)) return;
        JsonObject o = new JsonObject();
        o.addProperty("type", "join");
        o.addProperty("player", e.getPlayer().getName());
        o.addProperty("uuid", e.getPlayer().getUniqueId().toString());
        o.addProperty("time", Instant.now().toEpochMilli());
        if (showAllLog) getLogger().info("详细: 触发 join 事件，玩家: " + e.getPlayer().getName() + " | UUID: " + e.getPlayer().getUniqueId());
        sendJsonAsync(o);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        if (!getConfig().getBoolean("events.quit", true)) return;
        JsonObject o = new JsonObject();
        o.addProperty("type", "quit");
        o.addProperty("player", e.getPlayer().getName());
        o.addProperty("uuid", e.getPlayer().getUniqueId().toString());
        o.addProperty("time", Instant.now().toEpochMilli());
        if (showAllLog) getLogger().info("详细: 触发 quit 事件，玩家: " + e.getPlayer().getName());
        sendJsonAsync(o);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChat(AsyncPlayerChatEvent e) {
        if (!getConfig().getBoolean("events.chat", true)) return;
        if (e.isCancelled() && !getConfig().getBoolean("chat.send_cancelled", false)) return;
        String raw = ChatColor.stripColor(e.getMessage());
        JsonObject o = new JsonObject();
        o.addProperty("type", "chat");
        o.addProperty("player", e.getPlayer().getName());
        o.addProperty("uuid", e.getPlayer().getUniqueId().toString());
        o.addProperty("message", raw);
        o.addProperty("time", Instant.now().toEpochMilli());
        if (showAllLog) getLogger().info("详细: 触发 chat 事件，玩家: " + e.getPlayer().getName() + " | 消息: " + raw);
        sendJsonAsync(o);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        if (!getConfig().getBoolean("events.death", true)) return;
        Player player = e.getEntity();
        String deathMsg = e.getDeathMessage();
        if (deathMsg != null) {
            deathMsg = ChatColor.stripColor(deathMsg);
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "death");
        o.addProperty("player", player.getName());
        o.addProperty("uuid", player.getUniqueId().toString());
        o.addProperty("message", deathMsg != null ? deathMsg : "");
        o.addProperty("time", Instant.now().toEpochMilli());
        if (showAllLog) getLogger().info("详细: 触发 death 事件，玩家: " + player.getName() + " | 消息: " + (deathMsg != null ? deathMsg : "null"));
        sendJsonAsync(o);
    }
}