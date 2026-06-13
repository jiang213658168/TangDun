# backend/app/algorithms/evaluation/evaluator.py
# 模型评估器

import numpy as np
from typing import Dict, List, Tuple


class GlucoseModelEvaluator:
    """血糖预测模型评估器

    评估指标:
    - MAE (Mean Absolute Error): 平均绝对误差
    - RMSE (Root Mean Square Error): 均方根误差
    - Clarke误差网格: 临床准确性评估
    - TIR预测准确率: 目标范围内时间预测准确率
    """

    def __init__(self):
        """初始化评估器"""
        pass

    def calculate_mae(self, predicted: np.ndarray, actual: np.ndarray) -> float:
        """计算平均绝对误差

        Args:
            predicted: 预测值数组
            actual: 实际值数组

        Returns:
            MAE值
        """
        return float(np.mean(np.abs(predicted - actual)))

    def calculate_rmse(self, predicted: np.ndarray, actual: np.ndarray) -> float:
        """计算均方根误差

        Args:
            predicted: 预测值数组
            actual: 实际值数组

        Returns:
            RMSE值
        """
        return float(np.sqrt(np.mean((predicted - actual) ** 2)))

    def calculate_mape(self, predicted: np.ndarray, actual: np.ndarray) -> float:
        """计算平均绝对百分比误差

        Args:
            predicted: 预测值数组
            actual: 实际值数组

        Returns:
            MAPE值 (百分比)
        """
        # 避免除以0
        mask = actual != 0
        return float(np.mean(np.abs((actual[mask] - predicted[mask]) / actual[mask])) * 100)

    def clarke_error_grid(self, predicted: np.ndarray, actual: np.ndarray) -> Dict[str, float]:
        """Clarke误差网格分析

        将预测值与实际值的偏差分为A-E五个区域
        A区: 临床准确
        B区: 临床可接受
        C区: 可能导致错误处理
        D区: 危险的错误处理
        E区: 极危险的错误处理

        Args:
            predicted: 预测值数组 (mmol/L)
            actual: 实际值数组 (mmol/L)

        Returns:
            各区域的比例
        """
        n = len(predicted)
        zones = {'A': 0, 'B': 0, 'C': 0, 'D': 0, 'E': 0}

        for i in range(n):
            pred = predicted[i]
            act = actual[i]

            zone = self._get_clarke_zone(pred, act)
            zones[zone] += 1

        # 转换为百分比
        for zone in zones:
            zones[zone] = round(zones[zone] / n * 100, 1)

        return zones

    def _get_clarke_zone(self, predicted: float, actual: float) -> str:
        """判断单个点在Clarke误差网格中的区域

        Args:
            predicted: 预测值
            actual: 实际值

        Returns:
            区域字母 (A/B/C/D/E)
        """
        # A区判定
        if actual < 5.6:
            if abs(predicted - actual) <= 1.4:
                return 'A'
        else:
            if 0.8 * actual <= predicted <= 1.2 * actual:
                return 'A'

        # B区判定 (在A区之外但在可接受范围内)
        if actual < 5.6:
            if abs(predicted - actual) <= 2.8:
                return 'B'
        else:
            if 0.7 * actual <= predicted <= 1.3 * actual:
                return 'B'

        # C区判定 (可能导致错误处理)
        if actual >= 5.6 and predicted >= 1.3 * actual:
            return 'C'
        if actual >= 5.6 and predicted <= 0.7 * actual:
            return 'C'

        # D区判定 (危险的错误处理)
        if actual < 3.9 and predicted > 7.8:
            return 'D'
        if actual > 7.8 and predicted < 3.9:
            return 'D'

        # E区判定 (极危险的错误处理)
        if actual < 3.9 and predicted > 11.1:
            return 'E'
        if actual > 11.1 and predicted < 3.9:
            return 'E'

        # 默认返回B区
        return 'B'

    def calculate_clarke_a_b_percent(self, predicted: np.ndarray, actual: np.ndarray) -> float:
        """计算Clarke A+B区比例

        Args:
            predicted: 预测值数组
            actual: 实际值数组

        Returns:
            A+B区比例 (百分比)
        """
        zones = self.clarke_error_grid(predicted, actual)
        return zones['A'] + zones['B']

    def calculate_tir_accuracy(self, predicted: np.ndarray, actual: np.ndarray,
                                low_threshold: float = 3.9, high_threshold: float = 10.0) -> Dict[str, float]:
        """计算TIR预测准确率

        Args:
            predicted: 预测值数组
            actual: 实际值数组
            low_threshold: 低血糖阈值
            high_threshold: 高血糖阈值

        Returns:
            TIR准确率指标
        """
        # 实际TIR
        actual_in_range = np.sum((actual >= low_threshold) & (actual <= high_threshold))
        actual_tir = actual_in_range / len(actual) * 100

        # 预测TIR
        predicted_in_range = np.sum((predicted >= low_threshold) & (predicted <= high_threshold))
        predicted_tir = predicted_in_range / len(predicted) * 100

        # 分类准确率
        actual_class = np.where(actual < low_threshold, 0,
                               np.where(actual > high_threshold, 2, 1))
        predicted_class = np.where(predicted < low_threshold, 0,
                                  np.where(predicted > high_threshold, 2, 1))

        accuracy = np.sum(actual_class == predicted_class) / len(actual) * 100

        # 各类别准确率
        low_mask = actual_class == 0
        normal_mask = actual_class == 1
        high_mask = actual_class == 2

        low_accuracy = np.sum(predicted_class[low_mask] == 0) / np.sum(low_mask) * 100 if np.sum(low_mask) > 0 else 0
        normal_accuracy = np.sum(predicted_class[normal_mask] == 1) / np.sum(normal_mask) * 100 if np.sum(normal_mask) > 0 else 0
        high_accuracy = np.sum(predicted_class[high_mask] == 2) / np.sum(high_mask) * 100 if np.sum(high_mask) > 0 else 0

        return {
            'actual_tir': round(actual_tir, 1),
            'predicted_tir': round(predicted_tir, 1),
            'overall_accuracy': round(accuracy, 1),
            'low_accuracy': round(low_accuracy, 1),
            'normal_accuracy': round(normal_accuracy, 1),
            'high_accuracy': round(high_accuracy, 1)
        }

    def calculate_sensitivity_specificity(self, predicted: np.ndarray, actual: np.ndarray,
                                           threshold: float, condition: str = 'low') -> Dict[str, float]:
        """计算灵敏度和特异度

        Args:
            predicted: 预测值数组
            actual: 实际值数组
            threshold: 阈值
            condition: 条件 ('low' 或 'high')

        Returns:
            灵敏度和特异度
        """
        if condition == 'low':
            actual_positive = actual < threshold
            predicted_positive = predicted < threshold
        else:
            actual_positive = actual > threshold
            predicted_positive = predicted > threshold

        # 真阳性、假阳性、真阴性、假阴性
        tp = np.sum(actual_positive & predicted_positive)
        fp = np.sum(~actual_positive & predicted_positive)
        tn = np.sum(~actual_positive & ~predicted_positive)
        fn = np.sum(actual_positive & ~predicted_positive)

        # 灵敏度 = TP / (TP + FN)
        sensitivity = tp / (tp + fn) if (tp + fn) > 0 else 0

        # 特异度 = TN / (TN + FP)
        specificity = tn / (tn + fp) if (tn + fp) > 0 else 0

        return {
            'sensitivity': round(sensitivity, 3),
            'specificity': round(specificity, 3),
            'tp': int(tp),
            'fp': int(fp),
            'tn': int(tn),
            'fn': int(fn)
        }

    def evaluate_comprehensive(self, predicted: np.ndarray, actual: np.ndarray,
                                 horizons: List[int] = None) -> Dict:
        """综合评估

        Args:
            predicted: 预测值数组
            actual: 实际值数组
            horizons: 预测时域列表

        Returns:
            综合评估结果
        """
        results = {
            'overall': {
                'mae': self.calculate_mae(predicted, actual),
                'rmse': self.calculate_rmse(predicted, actual),
                'mape': self.calculate_mape(predicted, actual),
                'clarke_zones': self.clarke_error_grid(predicted, actual),
                'clarke_a_b_percent': self.calculate_clarke_a_b_percent(predicted, actual),
                'tir_accuracy': self.calculate_tir_accuracy(predicted, actual),
                'low_glucose': self.calculate_sensitivity_specificity(
                    predicted, actual, 3.9, 'low'
                ),
                'high_glucose': self.calculate_sensitivity_specificity(
                    predicted, actual, 10.0, 'high'
                )
            }
        }

        # 按时域评估
        if horizons:
            results['by_horizon'] = {}
            for horizon in horizons:
                # 假设predicted和actual是按时间排列的
                # 每horizon个点取一个
                pred_horizon = predicted[::horizon // 5] if horizon > 5 else predicted
                actual_horizon = actual[::horizon // 5] if horizon > 5 else actual

                results['by_horizon'][f'{horizon}min'] = {
                    'mae': self.calculate_mae(pred_horizon, actual_horizon),
                    'rmse': self.calculate_rmse(pred_horizon, actual_horizon),
                    'clarke_a_b_percent': self.calculate_clarke_a_b_percent(pred_horizon, actual_horizon)
                }

        return results

    def generate_report(self, evaluation_results: Dict) -> str:
        """生成评估报告

        Args:
            evaluation_results: 评估结果

        Returns:
            评估报告文本
        """
        overall = evaluation_results['overall']

        report = f"""
血糖预测模型评估报告
====================

整体指标:
- MAE: {overall['mae']:.2f} mmol/L
- RMSE: {overall['rmse']:.2f} mmol/L
- MAPE: {overall['mape']:.1f}%
- Clarke A+B区比例: {overall['clarke_a_b_percent']:.1f}%

Clarke误差网格分布:
- A区 (临床准确): {overall['clarke_zones']['A']:.1f}%
- B区 (临床可接受): {overall['clarke_zones']['B']:.1f}%
- C区 (可能错误): {overall['clarke_zones']['C']:.1f}%
- D区 (危险错误): {overall['clarke_zones']['D']:.1f}%
- E区 (极危险): {overall['clarke_zones']['E']:.1f}%

TIR预测准确率:
- 实际TIR: {overall['tir_accuracy']['actual_tir']:.1f}%
- 预测TIR: {overall['tir_accuracy']['predicted_tir']:.1f}%
- 分类准确率: {overall['tir_accuracy']['overall_accuracy']:.1f}%

低血糖检测:
- 灵敏度: {overall['low_glucose']['sensitivity']:.3f}
- 特异度: {overall['low_glucose']['specificity']:.3f}

高血糖检测:
- 灵敏度: {overall['high_glucose']['sensitivity']:.3f}
- 特异度: {overall['high_glucose']['specificity']:.3f}
"""

        return report
