// flutter/lib/widgets/glucose_chart.dart
// 血糖曲线图组件

import 'package:flutter/material.dart';
import 'package:fl_chart/fl_chart.dart';

class GlucoseChart extends StatelessWidget {
  final List<Map<String, dynamic>> data;
  final double targetLow;
  final double targetHigh;

  const GlucoseChart({
    Key? key,
    required this.data,
    required this.targetLow,
    required this.targetHigh,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    if (data.isEmpty) {
      return Center(
        child: Text('暂无数据'),
      );
    }

    return LineChart(
      LineChartData(
        gridData: FlGridData(
          show: true,
          drawVerticalLine: false,
          horizontalInterval: 2,
          getDrawingHorizontalLine: (value) {
            return FlLine(
              color: Colors.grey[300]!,
              strokeWidth: 0.5,
            );
          },
        ),
        titlesData: FlTitlesData(
          show: true,
          rightTitles: AxisTitles(
            sideTitles: SideTitles(showTitles: false),
          ),
          topTitles: AxisTitles(
            sideTitles: SideTitles(showTitles: false),
          ),
          bottomTitles: AxisTitles(
            sideTitles: SideTitles(
              showTitles: true,
              reservedSize: 30,
              interval: 4,
              getTitlesWidget: (value, meta) {
                final hour = value.toInt();
                if (hour % 4 == 0) {
                  return Text(
                    '${hour.toString().padLeft(2, '0')}:00',
                    style: TextStyle(
                      color: Colors.grey[600],
                      fontSize: 10,
                    ),
                  );
                }
                return Text('');
              },
            ),
          ),
          leftTitles: AxisTitles(
            sideTitles: SideTitles(
              showTitles: true,
              interval: 2,
              reservedSize: 40,
              getTitlesWidget: (value, meta) {
                return Text(
                  value.toStringAsFixed(0),
                  style: TextStyle(
                    color: Colors.grey[600],
                    fontSize: 10,
                  ),
                );
              },
            ),
          ),
        ),
        borderData: FlBorderData(
          show: false,
        ),
        minX: 0,
        maxX: 24,
        minY: 2,
        maxY: 14,
        lineBarsData: [
          // 目标范围区域
          LineChartBarData(
            spots: [],
            belowBarData: BarAreaData(
              show: true,
              color: Colors.green.withOpacity(0.1),
              cutOffY: targetHigh,
              applyCutOffY: true,
            ),
          ),
          // 血糖曲线
          LineChartBarData(
            spots: data.map((point) {
              return FlSpot(
                point['time'] as double,
                point['value'] as double,
              );
            }).toList(),
            isCurved: true,
            color: Color(0xFF007A8C),
            barWidth: 2,
            isStrokeCapRound: true,
            dotData: FlDotData(
              show: false,
            ),
            belowBarData: BarAreaData(
              show: true,
              color: Color(0xFF007A8C).withOpacity(0.1),
            ),
          ),
        ],
        extraLinesData: ExtraLinesData(
          horizontalLines: [
            HorizontalLine(
              y: targetLow,
              color: Colors.green.withOpacity(0.5),
              strokeWidth: 1,
              dashArray: [5, 5],
            ),
            HorizontalLine(
              y: targetHigh,
              color: Colors.orange.withOpacity(0.5),
              strokeWidth: 1,
              dashArray: [5, 5],
            ),
          ],
        ),
      ),
    );
  }
}
