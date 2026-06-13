// flutter/lib/widgets/prediction_chart.dart
// 预测曲线图组件

import 'package:flutter/material.dart';
import 'package:fl_chart/fl_chart.dart';

class PredictionChart extends StatelessWidget {
  final double currentGlucose;
  final Map<String, dynamic> predictions;
  final double targetLow;
  final double targetHigh;

  const PredictionChart({
    Key? key,
    required this.currentGlucose,
    required this.predictions,
    required this.targetLow,
    required this.targetHigh,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    // 生成预测曲线数据
    List<FlSpot> predictionSpots = [];
    List<FlSpot> upperSpots = [];
    List<FlSpot> lowerSpots = [];

    // 当前点
    predictionSpots.add(FlSpot(0, currentGlucose));
    upperSpots.add(FlSpot(0, currentGlucose));
    lowerSpots.add(FlSpot(0, currentGlucose));

    // 30分钟预测
    if (predictions.containsKey('horizon_30')) {
      final pred = predictions['horizon_30'];
      predictionSpots.add(FlSpot(30, pred['value']));
      upperSpots.add(FlSpot(30, pred['upper']));
      lowerSpots.add(FlSpot(30, pred['lower']));
    }

    // 60分钟预测
    if (predictions.containsKey('horizon_60')) {
      final pred = predictions['horizon_60'];
      predictionSpots.add(FlSpot(60, pred['value']));
      upperSpots.add(FlSpot(60, pred['upper']));
      lowerSpots.add(FlSpot(60, pred['lower']));
    }

    // 90分钟预测
    if (predictions.containsKey('horizon_90')) {
      final pred = predictions['horizon_90'];
      predictionSpots.add(FlSpot(90, pred['value']));
      upperSpots.add(FlSpot(90, pred['upper']));
      lowerSpots.add(FlSpot(90, pred['lower']));
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
              interval: 30,
              getTitlesWidget: (value, meta) {
                return Text(
                  '${value.toInt()}min',
                  style: TextStyle(
                    color: Colors.grey[600],
                    fontSize: 10,
                  ),
                );
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
        maxX: 90,
        minY: 2,
        maxY: 14,
        lineBarsData: [
          // 置信区间上界
          LineChartBarData(
            spots: upperSpots,
            isCurved: true,
            color: Colors.blue.withOpacity(0.3),
            barWidth: 1,
            isStrokeCapRound: true,
            dotData: FlDotData(
              show: false,
            ),
            belowBarData: BarAreaData(
              show: true,
              color: Colors.blue.withOpacity(0.1),
            ),
          ),
          // 置信区间下界
          LineChartBarData(
            spots: lowerSpots,
            isCurved: true,
            color: Colors.blue.withOpacity(0.3),
            barWidth: 1,
            isStrokeCapRound: true,
            dotData: FlDotData(
              show: false,
            ),
          ),
          // 预测曲线
          LineChartBarData(
            spots: predictionSpots,
            isCurved: true,
            color: Color(0xFF007A8C),
            barWidth: 3,
            isStrokeCapRound: true,
            dotData: FlDotData(
              show: true,
              getDotPainter: (spot, percent, barData, index) {
                return FlDotCirclePainter(
                  radius: 4,
                  color: Color(0xFF007A8C),
                  strokeWidth: 2,
                  strokeColor: Colors.white,
                );
              },
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
