# 糖盾部署文档

## 后端部署

### 环境要求

- Python 3.8+
- SQLite 3
- 依赖包见 requirements.txt

### 部署步骤

1. **克隆项目**
```bash
git clone <repository-url>
cd tangdun/backend
```

2. **创建虚拟环境**
```bash
python -m venv venv
source venv/bin/activate  # Linux/Mac
# 或
venv\Scripts\activate  # Windows
```

3. **安装依赖**
```bash
pip install -r requirements.txt
```

4. **配置环境变量**
```bash
cp .env.example .env
# 编辑 .env 文件，配置以下变量:
# - SECRET_KEY: JWT密钥
# - BAIDU_AI_API_KEY: 百度AI API Key
# - BAIDU_AI_SECRET_KEY: 百度AI Secret Key
```

5. **初始化数据库**
```bash
python -m app.db.init_db
```

6. **启动服务**
```bash
# 开发环境
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload

# 生产环境
gunicorn app.main:app -w 4 -k uvicorn.workers.UvicornWorker -b 0.0.0.0:8000
```

7. **验证服务**
```bash
curl http://localhost:8000/api/v1/health
```

### Docker部署

1. **构建镜像**
```bash
cd tangdun/backend
docker build -t tangdun-backend .
```

2. **运行容器**
```bash
docker run -d \
  -p 8000:8000 \
  -v $(pwd)/data:/app/data \
  -v $(pwd)/.env:/app/.env \
  --name tangdun-backend \
  tangdun-backend
```

### 数据库备份

```bash
# 备份SQLite数据库
cp backend/data/tangdun.db backend/data/tangdun_backup_$(date +%Y%m%d).db

# 自动备份脚本 (添加到crontab)
# 每天凌晨3点备份
0 3 * * * cp /path/to/tangdun/backend/data/tangdun.db /path/to/backup/tangdun_$(date +\%Y\%m\%d).db
```

## Flutter App部署

### 环境要求

- Flutter 3.16+
- Dart 3.0+
- Android Studio / Xcode

### 构建步骤

1. **安装依赖**
```bash
cd tangdun/flutter
flutter pub get
```

2. **配置API地址**
```dart
// lib/config.dart
static const String baseUrl = 'http://your-server-ip:8000';
```

3. **构建Android APK**
```bash
flutter build apk --release
```

4. **构建iOS IPA**
```bash
flutter build ios
```

5. **发布**
- Android: 将 `build/app/outputs/flutter-apk/app-release.apk` 分发给用户
- iOS: 通过App Store Connect发布

## Android同步App部署

### 环境要求

- JDK 17
- Android SDK 34
- Gradle 8.2

### 构建步骤

1. **配置环境变量**
```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
```

2. **构建APK**
```bash
cd tangdun/android
gradle assembleDebug  # 调试版
gradle assembleRelease  # 发布版
```

3. **安装到设备**
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 配置说明

- BASE_URL默认为 `http://10.0.2.2:8000` (模拟器访问宿主机)
- 真机测试需改为局域网IP，如 `http://192.168.1.100:8000`
- 首次启动需授予Health Connect权限

## 测试

### 运行后端测试

```bash
cd tangdun/backend
python -m pytest tests/ -v
```

### 运行Flutter测试

```bash
cd tangdun/flutter
flutter test
```

## 监控

### 健康检查

```bash
curl http://localhost:8000/api/v1/health
```

### 查看日志

```bash
# Docker日志
docker logs -f tangdun-backend

# 系统日志
tail -f /var/log/tangdun/app.log
```

## 故障排除

### 常见问题

1. **端口被占用**
```bash
# 查找占用端口的进程
lsof -i :8000
# 或
netstat -tulpn | grep 8000

# 杀死进程
kill -9 <PID>
```

2. **数据库锁定**
```bash
# 检查SQLite数据库状态
sqlite3 backend/data/tangdun.db "PRAGMA integrity_check;"
```

3. **依赖安装失败**
```bash
# 使用国内镜像
pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
```

4. **Flutter构建失败**
```bash
flutter clean
flutter pub get
flutter build apk
```
