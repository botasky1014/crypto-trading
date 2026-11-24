# macOS 本机环境准备

适用于 Apple 芯片、16GB 内存的 Mac。做完下面的步骤后运行 `scripts/doctor.sh`，所有必需项都应为 OK。

## 1. 基础工具

```bash
brew install git jq        # curl 是 macOS 自带的
```

不需要手动安装 JDK。Gradle Toolchain 会自动下载 Temurin 25。

## 2. Docker Desktop

请安装 Apple 芯片版（arm64）。打开 **Settings → Resources → Advanced**，按下表设置：

| 项 | 值 | 原因 |
| --- | --- | --- |
| Memory | **10 GB** | 中间件加服务估算约 7.8GB（design.md 10.1），留一些余量；剩下约 6GB 给 macOS、IDE 和浏览器 |
| Swap | **2 GB** | 内存峰值时用来缓冲，避免容器被 OOM 杀掉 |
| CPU limit | **6** | 给 IDE 和 Gradle 构建留出 CPU |

改完点 **Apply & restart**。`doctor.sh` 会检查内存是否 ≥ 10 GiB，没达到算失败；CPU 与建议值不一致时只给出提示，不算失败。Swap 无法从脚本中可靠读取，请在设置界面中手动确认。

本项目的所有镜像都必须支持 `linux/arm64`。

## 3. Node.js 与 pnpm

需要当前 LTS 版本的 Node.js（≥ 22.12），推荐用 fnm 管理版本：

```bash
brew install fnm
echo 'eval "$(fnm env --use-on-cd --shell zsh)"' >> ~/.zshrc
source ~/.zshrc
fnm install --lts
fnm default lts-latest
```

`~/.zshrc` 只在交互式终端中加载。Gradle、IntelliJ、Claude Code 等工具在后台启动的 shell 不会加载它，会找不到 `node`。所以还**必须**把 fnm 初始化写进 `~/.zprofile`，这个文件在登录 shell 中加载：

```bash
echo 'eval "$(fnm env --shell zsh)"' >> ~/.zprofile
```

改完之后，已经打开的终端、IDE 和 Claude Code 会话要重启才能生效。可以用 `zsh -l -c 'which node'` 确认：能输出路径就说明配置成功。

pnpm 通过 corepack 启用：

```bash
# Node.js 25 起不再自带 corepack，需要先单独安装（如果 `corepack -v` 能输出版本号，跳过这一步）
npm install -g corepack

corepack enable pnpm
pnpm -v
```

不要再用 Homebrew 或 npm 单独安装一份 pnpm。项目会在 `web/package.json` 的 `packageManager` 字段固定 pnpm 版本，由 corepack 负责切换。

## 4. IntelliJ IDEA

| 位置 | 设置 |
| --- | --- |
| File → Project Structure → Project → SDK | **Temurin 25**。没有的话点 Add SDK → Download JDK，选 Eclipse Temurin 25 |
| Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JVM | **Temurin 25**（或选 Project SDK）。仓库的 `gradle/gradle-daemon-jvm.properties` 也把 Gradle Daemon 固定为 Temurin 25：Spotless 使用的 Palantir Java Format 在更新的 JDK 上无法运行 |
| Settings → Build, Execution, Deployment → Compiler → Annotation Processors | 勾选 **Enable annotation processing**（Lombok、MapStruct 需要） |

运行配置放在仓库的 `.run/` 目录，随代码提交，IntelliJ 会自动识别。手工调接口用 IntelliJ HTTP Client，请求文件放在 `http/*.http`。

## 5. 磁盘

至少留出 **30GB** 可用空间，Docker 镜像、Gradle 缓存和 node_modules 都会占用空间。空间不够时可以执行 `docker system prune` 清理。

## 6. 自检

```bash
scripts/doctor.sh
```

全部必需项为 OK 时退出码为 0，有失败项时退出码为 1，每个失败项下面都会给出修复方法。
