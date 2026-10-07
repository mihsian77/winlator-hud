#!/usr/bin/env bash
# WinlatorHUD 本地编译验证脚本
#
# 用 ecj + android.jar 在本地真实编译 WinlatorHUD.java，交付前验证，
# 避免 CI 反复失败（试错提交）。
#
# 用法：
#   ./scripts/verify-local.sh                          # 验证核心库
#   ./scripts/verify-local.sh path/to/Other.java       # 验证指定文件
#
# 工具缓存：~/.cache/winlator-hud-verify/（首次自动下载）
# 依赖：java 11+（ecj 3.26 运行要求）

set -euo pipefail

CACHE_DIR="${VERIFY_CACHE_DIR:-$HOME/.cache/winlator-hud-verify}"
ECJ_URL="https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.26.0/ecj-3.26.0.jar"
ANDROID_JAR_URL="https://dl.google.com/android/repository/platform-34-ext7_r03.zip"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_SRC="$REPO_ROOT/winlator-hud/src/main/java/com/winlator/hud/WinlatorHUD.java"
SRC="${1:-$DEFAULT_SRC}"

echo "=== WinlatorHUD 本地编译验证 ==="
echo "源文件: $SRC"
echo "工具缓存: $CACHE_DIR"
mkdir -p "$CACHE_DIR"

# 1. ecj 编译器
if [ ! -f "$CACHE_DIR/ecj.jar" ]; then
    echo "[1/4] 下载 ecj 3.26.0 ..."
    curl -sL -o "$CACHE_DIR/ecj.jar" "$ECJ_URL"
fi

# 2. android.jar（官方 platform-34）
if [ ! -f "$CACHE_DIR/android.jar" ]; then
    echo "[2/4] 下载官方 android-34 platform ..."
    curl -sL -o "$CACHE_DIR/platform-34.zip" "$ANDROID_JAR_URL"
    unzip -o -q "$CACHE_DIR/platform-34.zip" "android-34/android.jar" -d "$CACHE_DIR"
    mv "$CACHE_DIR/android-34/android.jar" "$CACHE_DIR/android.jar"
    rm -rf "$CACHE_DIR/android-34" "$CACHE_DIR/platform-34.zip"
fi

# 3. lambda-stub.jar（android.jar 缺 LambdaMetafactory，从 JDK java.base 提取）
if [ ! -f "$CACHE_DIR/lambda-stub.jar" ]; then
    echo "[3/4] 从 JDK java.base 提取 LambdaMetafactory ..."
    # 寻找带 jmods 的 JDK：优先 VERIFY_JDK_HOME，再自动搜索常见路径
    find_jdk() {
        local jdk_home="${VERIFY_JDK_HOME:-}"
        if [ -z "$jdk_home" ]; then
            local java_real
            java_real="$(readlink -f "$(command -v java)" 2>/dev/null || true)"
            if [ -n "$java_real" ]; then
                jdk_home="$(dirname "$(dirname "$java_real")")"
            fi
        fi
        if [ -n "$jdk_home" ] && [ -f "$jdk_home/jmods/java.base.jmod" ]; then
            echo "$jdk_home"
            return 0
        fi
        for candidate in \
            "$HOME/.sdkman/candidates/java"/*/ \
            "$HOME/.jdks"/*/ \
            /opt/jdk* /opt/java* /usr/lib/jvm/java-17* /usr/lib/jvm/java-21* \
            "$HOME/Doubao/chats/38442007289826818/output/verify-tools"/jdk-17*; do
            if [ -f "$candidate/jmods/java.base.jmod" ]; then
                echo "$candidate"
                return 0
            fi
        done
        return 1
    }
    JDK_HOME="$(find_jdk || true)"
    if [ -z "$JDK_HOME" ]; then
        echo "错误: 未找到带 jmods 的 JDK"
        echo "可用 VERIFY_JDK_HOME=/path/to/jdk 指定，或安装完整 JDK（Temurin/OpenJDK 均可）"
        exit 1
    fi
    echo "使用 JDK: $JDK_HOME"
    TMP="$(mktemp -d)"
    # Temurin jmod 带 4 extra bytes，unzip 返回 1 但提取成功，容错处理
    unzip -o -q "$JDK_HOME/jmods/java.base.jmod" \
        "classes/java/lang/invoke/LambdaMetafactory.class" \
        "classes/java/lang/invoke/CallSite.class" \
        "classes/java/lang/invoke/MethodType.class" \
        -d "$TMP" || true
    if [ ! -f "$TMP/classes/java/lang/invoke/LambdaMetafactory.class" ]; then
        echo "错误: 从 jmod 提取 LambdaMetafactory 失败"
        rm -rf "$TMP"
        exit 1
    fi
    (cd "$TMP/classes" && zip -qr "$CACHE_DIR/lambda-stub.jar" .)
    rm -rf "$TMP"
fi

# 4. 编译
echo "[4/4] ecj 编译 ..."
rm -rf "$CACHE_DIR/out"
mkdir -p "$CACHE_DIR/out"
if java -jar "$CACHE_DIR/ecj.jar" \
    -source 1.8 -target 1.8 \
    -proc:none -warn:none \
    -bootclasspath "$CACHE_DIR/android.jar:$CACHE_DIR/lambda-stub.jar" \
    -d "$CACHE_DIR/out" "$SRC"; then
    COUNT="$(find "$CACHE_DIR/out" -name '*.class' | wc -l)"
    echo ""
    echo "✓ 编译通过，生成 $COUNT 个 class"
    exit 0
else
    echo ""
    echo "✗ 编译失败，修复后重新运行本脚本"
    exit 1
fi
