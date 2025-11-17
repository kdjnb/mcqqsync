# MCQQSync
## *Minecraft–QQ Synchronization Service (UDP Edition)*

一个用于 **Minecraft 服务器 ↔ QQ 群** 双向消息同步的轻量级插件。  
采用 **UDP 通讯**，简单、高效、无依赖，部署灵活。  
支持入服、离服、聊天、死亡等事件的实时转发。

---

## ✨ 功能特点

- ✔ **UDP 高性能通讯**，延迟低、资源占用小  
- ✔ 自动生成并管理 **Token 鉴权**  
- ✔ 支持事件转发：
  - 玩家加入（join）
  - 玩家离开（quit）
  - 玩家聊天（chat）
  - 玩家死亡（death）
- ✔ 可选：是否发送被取消的聊天事件  
- ✔ 支持详细日志（debug 模式）  
- ✔ 配置即时重载（/mcqqsync reload）  
- ✔ 控制台可管理 Token（get/reset）

---

## 📦 安装方式

1. 下载 MCQQSync 插件 jar  
2. 放入服务器 `plugins/` 目录  
3. 启动服务器自动生成配置文件与 Token  
4. 在你的 QQ 机器人 / 中转程序里配置对应的 UDP 地址与 Token

---

## ⚙ 配置文件（config.yml）

```yaml
udp_host: "127.0.0.1"
udp_port: 45345

chat:
  send_cancelled: false

events:
  join: true
  quit: true
  chat: true
  death: true

show_all_log: false
```

使用以下命令重载配置：

```
/mcqqsync reload
```

---

## 🔧 命令（Commands）

| 指令 | 权限 | 描述 |
|------|-------|--------|
| **/mcqqsync** | 所有人 | 显示插件信息 |
| **/mcqqsync reload** | 所有人 | 重载配置 |
| **/mcqqsync reconnect** | 所有人 | 重连 UDP Socket 并重发鉴权包(半弃用) |
| **/mcqqsync token get** | 控制台 | 查看当前 Token |
| **/mcqqsync token reset** | 控制台 | 重置 Token |

---

## 🔌 UDP 数据包格式

插件发送的所有数据均为 **JSON 字符串**。

示例：

```json
{
  "type": "chat",
  "player": "xx",
  "uuid": "xxxxxxxx-xxxx",
  "message": "你好",
  "time": 1731500000000,
  "token": "abcd1234"
}
```

---

## 📜 版本信息

- **当前版本：1.1.0（UDP版）**
- 作者：卡带酱（kdjnb）
- GitHub： https://github.com/kdjnb/mcqqsync
