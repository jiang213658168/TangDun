// flutter/lib/screens/report/report_screen.dart
// 报告主页 - 真实API调用

import 'package:flutter/material.dart';
import '../../services/api_service.dart';
import 'daily_report_screen.dart';
import 'weekly_report_screen.dart';
import 'monthly_report_screen.dart';

class ReportScreen extends StatefulWidget {
  @override
  _ReportScreenState createState() => _ReportScreenState();
}

class _ReportScreenState extends State<ReportScreen> {
  final _apiService = ApiService();
  bool isExporting = false;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('健康报告')),
      body: ListView(
        padding: EdgeInsets.all(16),
        children: [
          _buildReportCard(
            context,
            icon: Icons.today,
            title: '日报告',
            subtitle: '查看今日血糖、饮食、运动数据',
            onTap: () {
              Navigator.push(context, MaterialPageRoute(builder: (_) => DailyReportScreen()));
            },
          ),
          SizedBox(height: 12),
          _buildReportCard(
            context,
            icon: Icons.calendar_view_week,
            title: '周报告',
            subtitle: '查看本周血糖管理趋势',
            onTap: () {
              Navigator.push(context, MaterialPageRoute(builder: (_) => WeeklyReportScreen()));
            },
          ),
          SizedBox(height: 12),
          _buildReportCard(
            context,
            icon: Icons.calendar_month,
            title: '月报告',
            subtitle: '查看本月血糖管理总结',
            onTap: () {
              Navigator.push(context, MaterialPageRoute(builder: (_) => MonthlyReportScreen()));
            },
          ),
          SizedBox(height: 24),
          _buildExportSection(context),
        ],
      ),
    );
  }

  Widget _buildReportCard(
    BuildContext context, {
    required IconData icon,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
  }) {
    return Card(
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor: Color(0xFF007A8C),
          child: Icon(icon, color: Colors.white),
        ),
        title: Text(title, style: TextStyle(fontWeight: FontWeight.bold)),
        subtitle: Text(subtitle),
        trailing: Icon(Icons.chevron_right),
        onTap: onTap,
      ),
    );
  }

  Widget _buildExportSection(BuildContext context) {
    return Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('数据导出', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
            SizedBox(height: 12),
            if (isExporting)
              Center(child: CircularProgressIndicator())
            else
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: () => _showExportDialog(context),
                      icon: Icon(Icons.table_chart),
                      label: Text('导出CSV'),
                    ),
                  ),
                  SizedBox(width: 12),
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: () => _exportPDF(context),
                      icon: Icon(Icons.picture_as_pdf),
                      label: Text('导出PDF'),
                    ),
                  ),
                ],
              ),
          ],
        ),
      ),
    );
  }

  void _showExportDialog(BuildContext context) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('选择导出类型'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: Icon(Icons.bloodtype, color: Color(0xFF007A8C)),
              title: Text('血糖数据'),
              onTap: () {
                Navigator.pop(context);
                _exportCSV('glucose');
              },
            ),
            ListTile(
              leading: Icon(Icons.restaurant, color: Color(0xFF007A8C)),
              title: Text('饮食数据'),
              onTap: () {
                Navigator.pop(context);
                _exportCSV('meal');
              },
            ),
            ListTile(
              leading: Icon(Icons.directions_run, color: Color(0xFF007A8C)),
              title: Text('运动数据'),
              onTap: () {
                Navigator.pop(context);
                _exportCSV('exercise');
              },
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _exportCSV(String dataType) async {
    setState(() {
      isExporting = true;
    });

    try {
      final now = DateTime.now();
      final start = DateTime(now.year, now.month, 1);
      await _apiService.exportCsv(start: start, end: now, dataType: dataType);

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('CSV导出成功'),
          backgroundColor: Colors.green,
        ),
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('导出失败: $e'),
          backgroundColor: Colors.red,
        ),
      );
    } finally {
      setState(() {
        isExporting = false;
      });
    }
  }

  Future<void> _exportPDF(BuildContext context) async {
    // 显示PDF导出选项
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('导出PDF报告'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: Icon(Icons.today, color: Color(0xFF007A8C)),
              title: Text('日报告PDF'),
              onTap: () {
                Navigator.pop(context);
                _generatePDF('daily');
              },
            ),
            ListTile(
              leading: Icon(Icons.calendar_view_week, color: Color(0xFF007A8C)),
              title: Text('周报告PDF'),
              onTap: () {
                Navigator.pop(context);
                _generatePDF('weekly');
              },
            ),
            ListTile(
              leading: Icon(Icons.calendar_month, color: Color(0xFF007A8C)),
              title: Text('月报告PDF'),
              onTap: () {
                Navigator.pop(context);
                _generatePDF('monthly');
              },
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _generatePDF(String reportType) async {
    setState(() {
      isExporting = true;
    });

    try {
      // 获取报告数据
      Map<String, dynamic> data;
      String title;

      switch (reportType) {
        case 'daily':
          data = await _apiService.getDailyReport();
          title = '日报告';
          break;
        case 'weekly':
          data = await _apiService.getWeeklyReport();
          title = '周报告';
          break;
        case 'monthly':
          data = await _apiService.getMonthlyReport();
          title = '月报告';
          break;
        default:
          return;
      }

      // 显示预览对话框
      showDialog(
        context: context,
        builder: (context) => AlertDialog(
          title: Text('$title 预览'),
          content: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('报告类型: $title'),
                SizedBox(height: 8),
                if (data.containsKey('avg_glucose'))
                  Text('平均血糖: ${data['avg_glucose']} mmol/L'),
                if (data.containsKey('avg_tir'))
                  Text('平均TIR: ${data['avg_tir']}%'),
                SizedBox(height: 16),
                Text(
                  'PDF生成功能需要额外的PDF生成库支持。',
                  style: TextStyle(color: Colors.grey[600], fontSize: 12),
                ),
              ],
            ),
          ),
          actions: [
            ElevatedButton(
              onPressed: () => Navigator.pop(context),
              child: Text('确定'),
            ),
          ],
        ),
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('生成失败: $e'),
          backgroundColor: Colors.red,
        ),
      );
    } finally {
      setState(() {
        isExporting = false;
      });
    }
  }
}
