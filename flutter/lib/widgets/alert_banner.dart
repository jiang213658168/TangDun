// flutter/lib/widgets/alert_banner.dart
// 预警横幅组件

import 'package:flutter/material.dart';

class AlertBanner extends StatelessWidget {
  final String message;
  final String severity;
  final VoidCallback? onTap;

  const AlertBanner({
    Key? key,
    required this.message,
    required this.severity,
    this.onTap,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    Color backgroundColor;
    Color textColor;
    IconData icon;

    switch (severity) {
      case 'critical':
        backgroundColor = Colors.red[100]!;
        textColor = Colors.red[900]!;
        icon = Icons.dangerous;
        break;
      case 'warning':
        backgroundColor = Colors.orange[100]!;
        textColor = Colors.orange[900]!;
        icon = Icons.warning;
        break;
      default:
        backgroundColor = Colors.yellow[100]!;
        textColor = Colors.yellow[900]!;
        icon = Icons.info;
    }

    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: double.infinity,
        padding: EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        margin: EdgeInsets.only(bottom: 16),
        decoration: BoxDecoration(
          color: backgroundColor,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(
            color: textColor.withOpacity(0.3),
          ),
        ),
        child: Row(
          children: [
            Icon(
              icon,
              color: textColor,
              size: 24,
            ),
            SizedBox(width: 12),
            Expanded(
              child: Text(
                message,
                style: TextStyle(
                  color: textColor,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
            Icon(
              Icons.chevron_right,
              color: textColor,
            ),
          ],
        ),
      ),
    );
  }
}
