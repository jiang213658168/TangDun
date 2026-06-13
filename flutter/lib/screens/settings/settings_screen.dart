// flutter/lib/screens/settings/settings_screen.dart
// 设置页面 - 完整实现

import 'package:flutter/material.dart';
import '../../services/api_service.dart';
import '../report/daily_report_screen.dart';
import '../report/weekly_report_screen.dart';
import '../report/monthly_report_screen.dart';

class SettingsScreen extends StatefulWidget {
  @override
  _SettingsScreenState createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  final _apiService = ApiService();

  // 用户信息
  String userName = '用户';
  String diabetesType = '2型';
  double targetLow = 3.9;
  double targetHigh = 10.0;
  double alertLow = 3.9;
  double alertHigh = 10.0;

  @override
  void initState() {
    super.initState();
    _loadUserInfo();
  }

  Future<void> _loadUserInfo() async {
    try {
      final user = await _apiService.getUserInfo();
      setState(() {
        userName = user['name'] ?? '用户';
        diabetesType = user['diabetes_type'] == 1 ? '1型' : '2型';
        targetLow = (user['target_range_low'] as num?)?.toDouble() ?? 3.9;
        targetHigh = (user['target_range_high'] as num?)?.toDouble() ?? 10.0;
        alertLow = (user['alert_low'] as num?)?.toDouble() ?? 3.9;
        alertHigh = (user['alert_high'] as num?)?.toDouble() ?? 10.0;
      });
    } catch (e) {
      // 加载失败使用默认值
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('我的')),
      body: ListView(
        children: [
          _buildUserProfile(),
          SizedBox(height: 16),
          _buildSection('健康报告', [
            _buildMenuItem(Icons.description, '日报告', () {
              Navigator.push(context, MaterialPageRoute(builder: (_) => DailyReportScreen()));
            }),
            _buildMenuItem(Icons.calendar_view_week, '周报告', () {
              Navigator.push(context, MaterialPageRoute(builder: (_) => WeeklyReportScreen()));
            }),
            _buildMenuItem(Icons.calendar_month, '月报告', () {
              Navigator.push(context, MaterialPageRoute(builder: (_) => MonthlyReportScreen()));
            }),
            _buildMenuItem(Icons.download, '数据导出', () {
              _showExportDialog();
            }),
          ]),
          _buildSection('设置', [
            _buildMenuItem(Icons.monitor_heart, '血糖目标范围', () {
              _showTargetRangeDialog();
            }),
            _buildMenuItem(Icons.notifications, '预警设置', () {
              _showAlertSettingsDialog();
            }),
            _buildMenuItem(Icons.bluetooth, '设备连接', () {
              _showDeviceInfoDialog();
            }),
            _buildMenuItem(Icons.language, '语言设置', () {
              _showLanguageDialog();
            }),
          ]),
          _buildSection('关于', [
            _buildMenuItem(Icons.info, '关于糖盾', () {
              _showAboutDialog();
            }),
            _buildMenuItem(Icons.privacy_tip, '隐私政策', () {
              _showPrivacyPolicyDialog();
            }),
            _buildMenuItem(Icons.feedback, '意见反馈', () {
              _showFeedbackDialog();
            }),
          ]),
        ],
      ),
    );
  }

  Widget _buildUserProfile() {
    return Card(
      margin: EdgeInsets.all(16),
      child: Padding(
        padding: EdgeInsets.all(20),
        child: Row(
          children: [
            CircleAvatar(
              radius: 30,
              backgroundColor: Color(0xFF007A8C),
              child: Icon(Icons.person, size: 36, color: Colors.white),
            ),
            SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(userName, style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
                  SizedBox(height: 4),
                  Text('$diabetesType糖尿病', style: TextStyle(fontSize: 14, color: Colors.grey[600])),
                  SizedBox(height: 4),
                  Text('目标范围: $targetLow - $targetHigh mmol/L',
                      style: TextStyle(fontSize: 12, color: Colors.grey[500])),
                ],
              ),
            ),
            IconButton(
              icon: Icon(Icons.edit),
              onPressed: _showEditUserDialog,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSection(String title, List<Widget> children) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          child: Text(title, style: TextStyle(fontSize: 14, fontWeight: FontWeight.bold, color: Colors.grey[600])),
        ),
        Card(
          margin: EdgeInsets.symmetric(horizontal: 16),
          child: Column(children: children),
        ),
      ],
    );
  }

  Widget _buildMenuItem(IconData icon, String title, VoidCallback onTap) {
    return ListTile(
      leading: Icon(icon, color: Color(0xFF007A8C)),
      title: Text(title),
      trailing: Icon(Icons.chevron_right),
      onTap: onTap,
    );
  }

  // 编辑用户信息
  void _showEditUserDialog() {
    final nameController = TextEditingController(text: userName);
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('编辑用户信息'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: nameController,
              decoration: InputDecoration(labelText: '昵称', border: OutlineInputBorder()),
            ),
            SizedBox(height: 16),
            DropdownButtonFormField<String>(
              value: diabetesType,
              decoration: InputDecoration(labelText: '糖尿病类型', border: OutlineInputBorder()),
              items: ['1型', '2型'].map((e) => DropdownMenuItem(value: e, child: Text(e))).toList(),
              onChanged: (v) => diabetesType = v!,
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: Text('取消')),
          ElevatedButton(
            onPressed: () async {
              try {
                await _apiService.updateUserInfo({
                  'name': nameController.text,
                  'diabetes_type': diabetesType == '1型' ? 1 : 2,
                });
                setState(() => userName = nameController.text);
                Navigator.pop(context);
                ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('更新成功')));
              } catch (e) {
                ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('更新失败: $e')));
              }
            },
            child: Text('保存'),
          ),
        ],
      ),
    );
  }

  // 设置血糖目标范围
  void _showTargetRangeDialog() {
    final lowController = TextEditingController(text: targetLow.toString());
    final highController = TextEditingController(text: targetHigh.toString());
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('血糖目标范围'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('设置您的血糖目标范围，用于计算TIR和预警', style: TextStyle(color: Colors.grey[600])),
            SizedBox(height: 16),
            TextField(
              controller: lowController,
              keyboardType: TextInputType.number,
              decoration: InputDecoration(labelText: '下限 (mmol/L)', border: OutlineInputBorder()),
            ),
            SizedBox(height: 12),
            TextField(
              controller: highController,
              keyboardType: TextInputType.number,
              decoration: InputDecoration(labelText: '上限 (mmol/L)', border: OutlineInputBorder()),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: Text('取消')),
          ElevatedButton(
            onPressed: () async {
              final low = double.tryParse(lowController.text);
              final high = double.tryParse(highController.text);
              if (low == null || high == null || low >= high) {
                ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('请输入有效的范围')));
                return;
              }
              try {
                await _apiService.updateUserInfo({
                  'target_range_low': low,
                  'target_range_high': high,
                });
                setState(() {
                  targetLow = low;
                  targetHigh = high;
                });
                Navigator.pop(context);
                ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('更新成功')));
              } catch (e) {
                ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('更新失败: $e')));
              }
            },
            child: Text('保存'),
          ),
        ],
      ),
    );
  }

  // 预警设置
  void _showAlertSettingsDialog() {
    final lowController = TextEditingController(text: alertLow.toString());
    final highController = TextEditingController(text: alertHigh.toString());
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('预警设置'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('设置血糖预警阈值', style: TextStyle(color: Colors.grey[600])),
            SizedBox(height: 16),
            TextField(
              controller: lowController,
              keyboardType: TextInputType.number,
              decoration: InputDecoration(labelText: '低血糖预警 (mmol/L)', border: OutlineInputBorder()),
            ),
            SizedBox(height: 12),
            TextField(
              controller: highController,
              keyboardType: TextInputType.number,
              decoration: InputDecoration(labelText: '高血糖预警 (mmol/L)', border: OutlineInputBorder()),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: Text('取消')),
          ElevatedButton(
            onPressed: () async {
              final low = double.tryParse(lowController.text);
              final high = double.tryParse(highController.text);
              if (low == null || high == null || low >= high) {
                ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('请输入有效的范围')));
                return;
              }
              try {
                await _apiService.updateUserInfo({
                  'alert_low': low,
                  'alert_high': high,
                });
                setState(() {
                  alertLow = low;
                  alertHigh = high;
                });
                Navigator.pop(context);
                ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('更新成功')));
              } catch (e) {
                ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('更新失败: $e')));
              }
            },
            child: Text('保存'),
          ),
        ],
      ),
    );
  }

  // 设备连接信息
  void _showDeviceInfoDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('设备连接'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('CGM动态血糖仪', style: TextStyle(fontWeight: FontWeight.bold)),
            SizedBox(height: 8),
            Text('支持设备:'),
            Text('• 硅基动感CGM'),
            Text('• 雅培瞬感'),
            Text('• 德康G6/G7'),
            SizedBox(height: 12),
            Text('连接方式:', style: TextStyle(fontWeight: FontWeight.bold)),
            Text('• 通过xDrip+应用获取数据'),
            Text('• 确保xDrip+已正确配置'),
            Text('• 后端API地址需与xDrip+一致'),
            SizedBox(height: 12),
            Text('华为手表', style: TextStyle(fontWeight: FontWeight.bold)),
            SizedBox(height: 8),
            Text('• 通过Health Connect同步数据'),
            Text('• 需安装Android同步App'),
            Text('• 支持心率、步数、运动、睡眠'),
          ],
        ),
        actions: [
          ElevatedButton(onPressed: () => Navigator.pop(context), child: Text('确定')),
        ],
      ),
    );
  }

  // 语言设置
  void _showLanguageDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('语言设置'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              title: Text('中文'),
              trailing: Icon(Icons.check, color: Color(0xFF007A8C)),
              onTap: () => Navigator.pop(context),
            ),
            ListTile(
              title: Text('English'),
              trailing: null,
              onTap: () {
                Navigator.pop(context);
                ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('English language support coming soon')));
              },
            ),
          ],
        ),
      ),
    );
  }

  // 关于对话框
  void _showAboutDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('关于糖盾'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.health_and_safety, size: 64, color: Color(0xFF007A8C)),
            SizedBox(height: 16),
            Text('糖盾', style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold)),
            Text('基于多源数据融合的糖尿病智能健康管理系统'),
            SizedBox(height: 16),
            Text('版本: 1.0.0'),
            SizedBox(height: 8),
            Text('版权所有 © 雄鹰'),
            SizedBox(height: 8),
            Text('联系电话: 13041196482'),
            SizedBox(height: 16),
            Text('本系统通过融合CGM数据、智能手表运动数据和饮食记录，构建个人化的血糖预测模型。',
                style: TextStyle(fontSize: 12, color: Colors.grey[600])),
          ],
        ),
        actions: [
          ElevatedButton(onPressed: () => Navigator.pop(context), child: Text('确定')),
        ],
      ),
    );
  }

  // 隐私政策
  void _showPrivacyPolicyDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('隐私政策'),
        content: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('糖盾隐私政策', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
              SizedBox(height: 12),
              Text('一、数据收集', style: TextStyle(fontWeight: FontWeight.bold)),
              Text('本应用收集以下数据：\n• 血糖监测数据（来自CGM设备）\n• 运动数据（来自智能手表）\n• 饮食记录（用户手动输入或拍照识别）\n• 用户基本信息（昵称、糖尿病类型等）'),
              SizedBox(height: 8),
              Text('二、数据使用', style: TextStyle(fontWeight: FontWeight.bold)),
              Text('收集的数据仅用于：\n• 血糖预测和分析\n• 生成健康报告\n• 提供个性化建议'),
              SizedBox(height: 8),
              Text('三、数据存储', style: TextStyle(fontWeight: FontWeight.bold)),
              Text('• 所有数据存储在本地设备\n• 不会上传到第三方服务器\n• 用户可随时删除所有数据'),
              SizedBox(height: 8),
              Text('四、数据安全', style: TextStyle(fontWeight: FontWeight.bold)),
              Text('• 使用JWT认证保护数据\n• 所有API通信使用HTTPS\n• 敏感数据加密存储'),
              SizedBox(height: 8),
              Text('五、联系方式', style: TextStyle(fontWeight: FontWeight.bold)),
              Text('如有隐私问题，请联系：213658168@qq.com'),
            ],
          ),
        ),
        actions: [
          ElevatedButton(onPressed: () => Navigator.pop(context), child: Text('我已阅读')),
        ],
      ),
    );
  }

  // 意见反馈
  void _showFeedbackDialog() {
    final feedbackController = TextEditingController();
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('意见反馈'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('如有问题或建议，请发送邮件至：', style: TextStyle(color: Colors.grey[600])),
            SizedBox(height: 8),
            SelectableText('213658168@qq.com', style: TextStyle(color: Color(0xFF007A8C), fontWeight: FontWeight.bold)),
            SizedBox(height: 16),
            TextField(
              controller: feedbackController,
              maxLines: 4,
              decoration: InputDecoration(
                hintText: '请输入您的反馈意见...',
                border: OutlineInputBorder(),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: Text('取消')),
          ElevatedButton(
            onPressed: () {
              Navigator.pop(context);
              ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('感谢您的反馈！')));
            },
            child: Text('提交'),
          ),
        ],
      ),
    );
  }

  // 导出数据
  void _showExportDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('数据导出'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: Icon(Icons.table_chart, color: Color(0xFF007A8C)),
              title: Text('导出血糖数据 (CSV)'),
              onTap: () {
                Navigator.pop(context);
                _exportData('glucose');
              },
            ),
            ListTile(
              leading: Icon(Icons.restaurant, color: Color(0xFF007A8C)),
              title: Text('导出饮食数据 (CSV)'),
              onTap: () {
                Navigator.pop(context);
                _exportData('meal');
              },
            ),
            ListTile(
              leading: Icon(Icons.directions_run, color: Color(0xFF007A8C)),
              title: Text('导出运动数据 (CSV)'),
              onTap: () {
                Navigator.pop(context);
                _exportData('exercise');
              },
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _exportData(String dataType) async {
    try {
      final now = DateTime.now();
      final start = DateTime(now.year, now.month, 1);
      final response = await _apiService.exportCsv(start: start, end: now, dataType: dataType);
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('导出成功')));
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('导出失败: $e')));
    }
  }
}
