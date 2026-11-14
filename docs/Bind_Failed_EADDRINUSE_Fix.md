# `bind failed: EADDRINUSE (Address already in use)` 问题排查与修复记录

## 1. 问题现象

在重启服务、切换监听端口/网卡或服务销毁后迅速重建时，HTTP/HTTPS（或 RTSP）服务偶现或必现端口绑定失败：
```text
java.net.BindException: bind failed: EADDRINUSE (Address already in use)
```
导致 WebUI 或推流端口无法重新监听。

---

## 2. 根因分析

排查服务生命周期与 Socket 绑定逻辑后，发现以下三处直接原因：

### 2.1 `StreamingService.onDestroy()` 中使用了已取消的 `lifecycleScope`（核心根因）
在 [StreamingService.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/StreamingService.kt#L244-L265) 中：
- `onDestroy()` 首行即调用了 `super.onDestroy()`，此时 `LifecycleService` 的 `lifecycleScope` 已被标记为 **Cancelled**。
- 第 263 行原先使用：
  ```kotlin
  lifecycleScope.launch(Dispatchers.IO) { streamingServerHelper?.stopStreamingServer() }
  ```
  由于 `lifecycleScope` 已取消，该协程**根本不会执行**！导致 `StreamingServerHelper` 持有的 `serverSocket` 从未被关闭，旧的 `accept()` 循环在后台线程持续占用端口。
- 当同一进程内再次启动 `StreamingService` 时，新的 `StreamingServerHelper` 尝试绑定相同端口，立即触发 `EADDRINUSE`。

### 2.2 TLS `SSLServerSocket` 在 `bind()` 之后才设置 `reuseAddress = true`
在 [TlsHelper.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/TlsHelper.kt#L28-L55) 中：
- 原实现直接调用 `serverSocketFactory.createServerSocket(port, backlog, address)`，该方法在返回前就已经完成了底层 `bind()`。
- 之后再设置 `socket.reuseAddress = true`（`SO_REUSEADDR`）对已经完成的 `bind()` **无效**。在开启 HTTPS/TLS 且端口存在 `TIME_WAIT` 连接时，重启服务会直接报 `EADDRINUSE`。

### 2.3 绑定重试的异常匹配不够全面
在 [StreamingServerHelper.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/StreamingServerHelper.kt#L247-L255) 的 `bindServerSocket()` 中：
- 原先仅匹配区分大小写的 `"Address already in use"` 等字符串，未直接判断 `e is java.net.BindException` 及忽略大小写匹配。

---

## 3. 修复方案

1. **修复 `StreamingService.onDestroy()` 中的端口释放**（[StreamingService.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/StreamingService.kt#L263-L265)）：
   - 改为使用独立线程执行 `runBlocking { helper.stopStreamingServer() }`（与同方法中 `rtspServer` 的释放方式一致），确保在 `lifecycleScope` 已取消的情况下仍能可靠关闭 `serverSocket` 与所有活跃连接。
2. **修复 `TlsHelper.createServerSocket()` 的 `SO_REUSEADDR` 时机**（[TlsHelper.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/TlsHelper.kt#L34-L55)）：
   - 优先调用无参 `serverSocketFactory.createServerSocket()` 创建未绑定 `SSLServerSocket`，先设置 `reuseAddress = true`，再执行 `bind(InetSocketAddress(address, port), backlog)`；若 `bind()` 失败则及时关闭临时 socket；仅在平台抛出 `UnsupportedOperationException` 时回退到三参数方法。
3. **增强 `bindServerSocket()` 的重试判定**（[StreamingServerHelper.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/StreamingServerHelper.kt#L247-L255)）：
   - 增加 `e is java.net.BindException` 与 `ignoreCase = true` 匹配，确保 `TIME_WAIT` 场景下稳定触发退避重试。
