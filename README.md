# OpenList TV

在 Android 电视上常驻一个 [OpenList](https://github.com/OpenListTeam/OpenList)(AList 的社区分支)服务器,并用遥控器浏览、播放里面的文件。相当于 Apple TV 上的 AlistServer。

- **官方内核**:直接运行 OpenList 官方发布的 32 位安卓服务器程序(`openlist-android-arm`),不改它的代码;
- **常驻**:开机自启、被强制停止后由系统拉起、服务器进程意外退出自动重启;
- **遥控器界面**:运行状态、管理页地址和二维码、管理员密码、日志;
- **浏览文件**:一层层浏览挂载的网盘和本地文件夹,选中视频按 OK 交给默认播放器(Nova、VLC、小米播放器……)。

在小米电视 MiTV-MFTR0(Android 11,32 位系统)上开发和测试。

## 为什么不用官方 OpenList-Mobile

官方手机版在这台小米电视上实测有三个问题:

1. 小米系统拒绝第三方应用的前台服务(日志:`AppsRelaunchController: Refused to start foreground service`),切到别的应用后随时可能被清掉;
2. 小米开机会强制停止所有第三方应用,开机自启收不到广播;
3. 界面是给触屏做的,遥控器有些地方到不了。

OpenList TV 借用「已授权的通知监听服务会被系统开机后、强制停止后重新绑定」这一点常驻(不读取任何通知),再由它托着服务器进程。

## 安装

```bash
scripts/fetch-core.sh            # 从 OpenList 官方发布下载服务器程序并核对 sha256
./gradlew assembleDebug          # 或在装有 Android SDK 的 Docker 镜像里编译
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd notification allow_listener com.lfkdsk.openlisttv/com.lfkdsk.openlisttv.KeepAliveListener   # 常驻
adb shell appops set com.lfkdsk.openlisttv MANAGE_EXTERNAL_STORAGE allow   # 挂电视本地文件夹时需要
```

授权常驻后服务器会立刻在后台启动,不用打开界面。管理页是 `http://电视IP:5244`,WebDAV 是 `http://电视IP:5244/dav`。

## 使用

- **第一次使用**:新版 OpenList 不再自动生成初始密码。用手机扫 OpenList TV 首页的二维码打开管理页,按安装向导自己设置管理员账号和密码。忘了密码可以在首页按「重置管理员密码」(连按两次确认,给 admin 换一个随机密码,只显示一次)。
- **网盘和本地文件夹**:在手机或电脑浏览器打开管理页添加。电视本地文件夹路径形如 `/storage/emulated/0/Movies`。
- **看片免密码**:管理页 → 用户 → 打开 `guest` 并只给读取权限,局域网里的播放器、本应用的浏览就都不用登录;管理员密码照样保留。
- **浏览文件**:首页「浏览文件」。OK 打开,长按 OK 临时换播放器,菜单键设默认播放器,返回键回上一级。没开访客时,第一次浏览会弹登录框(可以用手机通过电视键盘输入),之后记住,服务器重启后自动重新登录。

## 结构

```text
app/src/main/java/com/lfkdsk/openlisttv/
  OpenListServer.java    服务器进程:启动 / 停止 / 退避重启 / 日志 / 初始密码 / 重置密码
  ServerService.java     托管服务(前台服务被拒也没关系)
  KeepAliveListener.java 常驻和开机拉起(通知监听服务,不读通知)
  MainActivity.java      首页:状态、二维码、管理员、按钮
  BrowserActivity.java   文件浏览
  OpenListApi.java       调本机 OpenList 接口:登录 → 列目录
  Players.java           默认播放器
  Core.java / Paths.java 纯逻辑(有单元测试)
scripts/fetch-core.sh    下载官方服务器程序
```

## 说明

- 只打了 armeabi-v7a。64 位系统的电视可以把 `fetch-core.sh` 改成下载 `openlist-android-arm64`、放进 `arm64-v8a`。
- 记住的登录密码存在应用私有目录里,没有加密。
- 编译时要挂固定的调试签名(`~/.android/debug.keystore`),否则每次在新容器里编译签名都不一样,电视上无法覆盖更新。
- OpenList 以 AGPL-3.0 发布;本仓库不包含它的代码和二进制,APK 里打包的是官方发布的原版程序。
