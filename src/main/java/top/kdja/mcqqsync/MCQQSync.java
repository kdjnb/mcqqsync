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
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.*;

public class MCQQSync extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    final String version="1.1.0";  // UDP 版
    private DatagramSocket udpSocket;
    private String udpHost;
    private int udpPort;
    private String token;
    private File tokenFile;
    private boolean showAllLog = false;  // 详细日志开关

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // 设置默认配置
        getConfig().addDefault("udp_host", "127.0.0.1");  // UDP 主机
        getConfig().addDefault("udp_port", 45345);  // UDP 端口
        getConfig().addDefault("chat.send_cancelled", false);
        getConfig().addDefault("events.join", true);
        getConfig().addDefault("events.quit", true);
        getConfig().addDefault("events.chat", true);
        getConfig().addDefault("events.death", true);
        getConfig().addDefault("show_all_log", false);
        getConfig().options().copyDefaults(true);
        saveConfig();

        showAllLog = getConfig().getBoolean("show_all_log", false);
        udpHost = getConfig().getString("udp_host", "127.0.0.1");
        udpPort = getConfig().getInt("udp_port", 45345);

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

        // 初始化 UDP
        try {
            udpSocket = new DatagramSocket();  // 客户端用，随机本地端口
            getLogger().info("UDP 已初始化，发送到 " + udpHost + ":" + udpPort);
        } catch (IOException e) {
            getLogger().severe("UDP 初始化失败: " + e.getMessage());
            return;
        }

        // 发送初始 auth 包
        sendAuthPacket();

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("mcqqsync")).setExecutor(this);
        Objects.requireNonNull(getCommand("mcqqsync")).setTabCompleter(this);
        getLogger().info("MCQQSync (UDP版) 已启用");
    }

    @Override
    public void onDisable() {
        if (udpSocket != null) {
            udpSocket.close();
        }
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
            sender.sendMessage(ChatColor.GREEN + "MCQQSync v"+version+" (UDP版) - By 卡带酱 - https://github.com/kdjnb/mcqqsync");
            sender.sendMessage(ChatColor.YELLOW + "子命令: reload (重载), reconnect (重连 UDP), token <get|reset> (仅控制台)");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            showAllLog = getConfig().getBoolean("show_all_log", false);
            udpHost = getConfig().getString("udp_host", "127.0.0.1");
            udpPort = getConfig().getInt("udp_port", 45345);
            sender.sendMessage(ChatColor.GREEN + "配置已重载（UDP: " + udpHost + ":" + udpPort + " | 日志: " + (showAllLog ? "启用" : "禁用") + "）");
            return true;
        } else if (args[0].equalsIgnoreCase("reconnect")) {
            if (udpSocket != null) {
                udpSocket.close();
            }
            try {
                udpSocket = new DatagramSocket();
                getLogger().info("UDP 已重连");
                sendAuthPacket();  // 重新发 auth
            } catch (IOException e) {
                getLogger().warning("UDP 重连失败: " + e.getMessage());
            }
            sender.sendMessage(ChatColor.GREEN + "UDP 已重连");
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

    private void sendAuthPacket() {
        JsonObject auth = new JsonObject();
        auth.addProperty("type", "auth");
        auth.addProperty("token", token);
        sendUdpPacket(auth.toString());
        getLogger().info("已发送 UDP auth 包 (Token: " + token + ")");
    }

    private void sendUdpPacket(String jsonStr) {
        if (udpSocket == null || udpSocket.isClosed()) {
            getLogger().warning("UDP 未连接; 丢包: " + jsonStr);
            return;
        }
        try {
            byte[] data = jsonStr.getBytes();
            InetAddress address = InetAddress.getByName(udpHost);
            DatagramPacket packet = new DatagramPacket(data, data.length, address, udpPort);
            udpSocket.send(packet);
            if (showAllLog) getLogger().info("详细: UDP 发送成功，包大小: " + data.length + " bytes 到 " + udpHost + ":" + udpPort + " | 内容: " + jsonStr.substring(0, Math.min(50, jsonStr.length())) + (jsonStr.length() > 50 ? "..." : ""));
        } catch (IOException e) {
            getLogger().warning("UDP 发送失败: " + e.getMessage());
            if (showAllLog) e.printStackTrace();
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        if (!getConfig().getBoolean("events.join", true)) return;
        JsonObject o = new JsonObject();
        o.addProperty("type", "join");
        o.addProperty("player", e.getPlayer().getName());
        o.addProperty("uuid", e.getPlayer().getUniqueId().toString());
        o.addProperty("time", Instant.now().toEpochMilli());
        o.addProperty("token", token);  // 加 token
        if (showAllLog) getLogger().info("详细: 触发 join 事件，玩家: " + e.getPlayer().getName());
        sendUdpPacket(o.toString());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        if (!getConfig().getBoolean("events.quit", true)) return;
        JsonObject o = new JsonObject();
        o.addProperty("type", "quit");
        o.addProperty("player", e.getPlayer().getName());
        o.addProperty("uuid", e.getPlayer().getUniqueId().toString());
        o.addProperty("time", Instant.now().toEpochMilli());
        o.addProperty("token", token);  // 加 token
        if (showAllLog) getLogger().info("详细: 触发 quit 事件，玩家: " + e.getPlayer().getName());
        sendUdpPacket(o.toString());
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
        o.addProperty("token", token);  // 加 token
        if (showAllLog) getLogger().info("详细: 触发 chat 事件，玩家: " + e.getPlayer().getName() + " | 消息: " + raw);
        sendUdpPacket(o.toString());
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
        o.addProperty("token", token);  // 加 token
        if (showAllLog) getLogger().info("详细: 触发 death 事件，玩家: " + player.getName() + " | 消息: " + (deathMsg != null ? deathMsg : "null"));
        sendUdpPacket(o.toString());
    }
}