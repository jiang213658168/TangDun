# backend/app/algorithms/fusion/bma.py
# 贝叶斯模型平均 (Bayesian Model Averaging)

import numpy as np
from typing import Dict, List, Optional


class BayesianModelAveraging:
    """贝叶斯模型平均

    根据各模型的近期预测表现动态调整融合权重
    权重计算公式: w_i = exp(-lambda * EWMA_error_i) / sum(exp(-lambda * EWMA_error_j))
    """

    def __init__(self, model_names: List[str], lambda_param: float = 0.5, decay_factor: float = 0.95):
        """初始化BMA

        Args:
            model_names: 模型名称列表
            lambda_param: 温度参数
            decay_factor: EWMA衰减因子
        """
        self.model_names = model_names
        self.lambda_param = lambda_param
        self.decay_factor = decay_factor

        # 初始化EWMA误差
        self.ewma_errors = {name: 0.0 for name in model_names}

        # 初始化权重 (均匀分布)
        self.weights = {name: 1.0 / len(model_names) for name in model_names}

        # 历史记录
        self.history = []

    def update_weights(self, predictions: Dict[str, float], actual: float):
        """更新模型权重

        Args:
            predictions: 各模型的预测值 {model_name: prediction}
            actual: 实际值
        """
        # 计算各模型的误差
        for name in self.model_names:
            if name in predictions:
                error = abs(predictions[name] - actual)
                # 更新EWMA误差
                self.ewma_errors[name] = (
                    self.decay_factor * self.ewma_errors[name] +
                    (1 - self.decay_factor) * error
                )

        # 计算新权重
        weights = {}
        for name in self.model_names:
            weights[name] = np.exp(-self.lambda_param * self.ewma_errors[name])

        # 归一化
        total = sum(weights.values())
        self.weights = {name: w / total for name, w in weights.items()}

        # 记录历史
        self.history.append({
            'weights': self.weights.copy(),
            'ewma_errors': self.ewma_errors.copy()
        })

    def fuse_predictions(self, predictions: Dict[str, Dict]) -> Dict:
        """融合各模型的预测结果

        Args:
            predictions: 各模型的预测结果
                {
                    'bergman': {'value': 7.5, 'upper': 8.0, 'lower': 7.0},
                    'transformer_lstm': {'value': 7.8, 'upper': 8.5, 'lower': 7.1}
                }

        Returns:
            融合后的预测结果
        """
        fused = {
            'value': 0.0,
            'upper': 0.0,
            'lower': 0.0,
            'weights': self.weights.copy(),
            'models': {}
        }

        # 加权平均
        for name in self.model_names:
            if name in predictions:
                pred = predictions[name]
                weight = self.weights.get(name, 0)

                fused['value'] += pred['value'] * weight
                fused['upper'] += pred['upper'] * weight
                fused['lower'] += pred['lower'] * weight

                fused['models'][name] = {
                    'value': pred['value'],
                    'weight': weight
                }

        # 计算不确定性
        values = [pred['value'] for pred in predictions.values()]
        fused['uncertainty'] = np.std(values) if len(values) > 1 else 0

        return fused

    def fuse_risk_levels(self, risk_levels: Dict[str, str]) -> str:
        """融合各模型的风险等级

        Args:
            risk_levels: 各模型的风险等级

        Returns:
            融合后的风险等级
        """
        # 风险等级优先级
        priority = {'high_risk': 3, 'low_risk': 2, 'normal': 1}

        # 加权投票
        votes = {'normal': 0, 'low_risk': 0, 'high_risk': 0}

        for name, level in risk_levels.items():
            weight = self.weights.get(name, 0)
            votes[level] += weight

        # 返回得票最高的风险等级
        return max(votes, key=votes.get)

    def get_weights(self) -> Dict[str, float]:
        """获取当前权重

        Returns:
            权重字典
        """
        return self.weights.copy()

    def get_ewma_errors(self) -> Dict[str, float]:
        """获取EWMA误差

        Returns:
            EWMA误差字典
        """
        return self.ewma_errors.copy()

    def reset(self):
        """重置BMA状态"""
        self.ewma_errors = {name: 0.0 for name in self.model_names}
        self.weights = {name: 1.0 / len(self.model_names) for name in self.model_names}
        self.history = []


class EnsemblePredictor:
    """集成预测器

    结合Bergman模型和Transformer-LSTM模型的预测结果
    """

    def __init__(self, bergman_model, transformer_lstm_model):
        """初始化集成预测器

        Args:
            bergman_model: Bergman模型实例
            transformer_lstm_model: Transformer-LSTM模型实例
        """
        self.bergman = bergman_model
        self.transformer_lstm = transformer_lstm_model
        self.bma = BayesianModelAveraging(['bergman', 'transformer_lstm'])

    def predict(self, initial_state, time_points, features,
                meals=None, exercises=None, horizons=[30, 60, 90]) -> Dict:
        """集成预测

        Args:
            initial_state: 初始状态 (G0, X0, I0)
            time_points: 时间点数组
            features: 特征字典
            meals: 饮食事件
            exercises: 运动事件
            horizons: 预测时域

        Returns:
            融合后的预测结果
        """
        predictions = {}

        # Bergman模型预测
        try:
            bergman_pred = self.bergman.predict(initial_state, time_points, meals, exercises)
            # 取最后一个值作为预测结果
            bergman_value = bergman_pred[-1]
            bergman_std = np.std(bergman_pred[-12:]) if len(bergman_pred) >= 12 else 1.0
            predictions['bergman'] = {
                'value': float(bergman_value),
                'upper': float(bergman_value + 1.96 * bergman_std),
                'lower': float(bergman_value - 1.96 * bergman_std)
            }
        except Exception as e:
            print(f"Bergman预测失败: {e}")

        # Transformer-LSTM模型预测
        try:
            if self.transformer_lstm.is_trained:
                lstm_result = self.transformer_lstm.predict(features, horizons)
                for horizon in horizons:
                    key = f'horizon_{horizon}'
                    if key in lstm_result['predictions']:
                        pred = lstm_result['predictions'][key]
                        predictions['transformer_lstm'] = pred
                        break
        except Exception as e:
            print(f"Transformer-LSTM预测失败: {e}")

        # BMA融合
        if len(predictions) > 0:
            fused = self.bma.fuse_predictions(predictions)
        else:
            # 如果所有模型都失败，返回默认值
            fused = {
                'value': initial_state[0],
                'upper': initial_state[0] + 2.0,
                'lower': initial_state[0] - 2.0,
                'weights': {'default': 1.0},
                'models': {}
            }

        return fused

    def update(self, predictions: Dict[str, float], actual: float):
        """更新模型权重

        Args:
            predictions: 各模型的预测值
            actual: 实际值
        """
        self.bma.update_weights(predictions, actual)

    def get_model_weights(self) -> Dict[str, float]:
        """获取模型权重

        Returns:
            权重字典
        """
        return self.bma.get_weights()
