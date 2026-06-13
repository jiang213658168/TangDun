# backend/app/algorithms/bergman/bayesian_estimator.py
# 贝叶斯参数估计器

import numpy as np
from scipy.optimize import minimize
from typing import Tuple, Dict, Optional
from app.algorithms.bergman.parameters import BergmanParameters
from app.algorithms.bergman.model import BergmanModel


class BayesianEstimator:
    """贝叶斯参数估计器

    使用最大后验概率(MAP)估计模型参数
    先验分布: 正态分布 (均值取默认参数值，标准差取默认值的50%)
    似然函数: 高斯似然 (预测值与实际值的误差)
    优化器: L-BFGS-B算法
    """

    def __init__(self, prior_params: Optional[BergmanParameters] = None):
        """初始化贝叶斯估计器

        Args:
            prior_params: 先验参数，如果为None则使用默认参数
        """
        self.prior_params = prior_params or BergmanParameters()
        self.prior_mean = self.prior_params.to_array()
        self.prior_std = self.prior_mean * 0.5  # 标准差取默认值的50%

    def _log_prior(self, params: np.ndarray) -> float:
        """计算先验概率的对数

        Args:
            params: 参数数组

        Returns:
            先验概率对数
        """
        # 正态分布先验
        log_prior = -0.5 * np.sum(((params - self.prior_mean) / self.prior_std) ** 2)
        return log_prior

    def _log_likelihood(self, params: np.ndarray, model: BergmanModel,
                        initial_state: Tuple[float, float, float],
                        time_points: np.ndarray,
                        observed_glucose: np.ndarray,
                        meals: list = None,
                        exercises: list = None) -> float:
        """计算似然函数的对数

        Args:
            params: 参数数组
            model: Bergman模型
            initial_state: 初始状态
            time_points: 时间点
            observed_glucose: 观测血糖值
            meals: 饮食事件
            exercises: 运动事件

        Returns:
            似然函数对数
        """
        try:
            # 使用参数创建模型
            model_params = BergmanParameters.from_array(params)
            model.params = model_params

            # 预测
            predicted = model.predict(initial_state, time_points, meals, exercises)

            # 高斯似然 (假设观测噪声标准差为0.5 mmol/L)
            sigma = 0.5
            residuals = observed_glucose - predicted
            log_likelihood = -0.5 * np.sum((residuals / sigma) ** 2)

            return log_likelihood
        except Exception:
            return -np.inf

    def _negative_log_posterior(self, params: np.ndarray, model: BergmanModel,
                                 initial_state: Tuple[float, float, float],
                                 time_points: np.ndarray,
                                 observed_glucose: np.ndarray,
                                 meals: list = None,
                                 exercises: list = None) -> float:
        """计算负对数后验概率

        Args:
            params: 参数数组
            model: Bergman模型
            initial_state: 初始状态
            time_points: 时间点
            observed_glucose: 观测血糖值
            meals: 饮食事件
            exercises: 运动事件

        Returns:
            负对数后验概率
        """
        log_prior = self._log_prior(params)
        log_likelihood = self._log_likelihood(
            params, model, initial_state, time_points, observed_glucose, meals, exercises
        )

        # 负对数后验 = -(先验 + 似然)
        return -(log_prior + log_likelihood)

    def estimate(self, observed_glucose: np.ndarray,
                  time_points: np.ndarray,
                  initial_state: Tuple[float, float, float],
                  meals: list = None,
                  exercises: list = None) -> Dict:
        """执行MAP参数估计

        Args:
            observed_glucose: 观测血糖值数组
            time_points: 时间点数组
            initial_state: 初始状态 (G0, X0, I0)
            meals: 饮食事件列表
            exercises: 运动事件列表

        Returns:
            估计结果字典
        """
        model = BergmanModel(self.prior_params)

        # 优化
        result = minimize(
            self._negative_log_posterior,
            self.prior_mean,
            args=(model, initial_state, time_points, observed_glucose, meals, exercises),
            method='L-BFGS-B',
            bounds=list(zip(*self.prior_params.bounds)),
            options={'maxiter': 1000}
        )

        # 提取最优参数
        optimal_params = result.x
        optimal_model_params = BergmanParameters.from_array(optimal_params)

        # 使用最优参数进行预测
        model.params = optimal_model_params
        predicted = model.predict(initial_state, time_points, meals, exercises)

        # 计算误差
        mae = np.mean(np.abs(predicted - observed_glucose))
        rmse = np.sqrt(np.mean((predicted - observed_glucose) ** 2))

        return {
            'params': optimal_model_params.to_dict(),
            'predicted': predicted,
            'mae': float(mae),
            'rmse': float(rmse),
            'success': result.success,
            'message': result.message
        }

    def online_update(self, current_params: BergmanParameters,
                       glucose_history: np.ndarray,
                       meals: list = None,
                       exercises: list = None,
                       learning_rate: float = 0.01) -> BergmanParameters:
        """在线更新参数（使用数值梯度）

        Args:
            current_params: 当前参数
            glucose_history: 最近的血糖观测值
            meals: 最近的饮食事件
            exercises: 最近的运动事件
            learning_rate: 学习率

        Returns:
            更新后的参数
        """
        from app.algorithms.bergman.model import BergmanModel

        model = BergmanModel(current_params)

        # 计算当前预测
        time_points = np.arange(0, len(glucose_history) * 5, 5)
        initial_state = (glucose_history[0], 0, 10.0)

        predicted = model.predict(initial_state, time_points, meals, exercises)

        # 计算损失 (MSE)
        min_len = min(len(predicted), len(glucose_history))
        loss = np.mean((predicted[:min_len] - glucose_history[:min_len]) ** 2)

        # 数值梯度计算
        params_array = current_params.to_array()
        epsilon = 1e-6
        gradients = np.zeros_like(params_array)

        for i in range(len(params_array)):
            # 正向扰动
            params_plus = params_array.copy()
            params_plus[i] += epsilon
            model_plus = BergmanModel(BergmanParameters.from_array(params_plus))
            pred_plus = model_plus.predict(initial_state, time_points, meals, exercises)
            loss_plus = np.mean((pred_plus[:min_len] - glucose_history[:min_len]) ** 2)

            # 负向扰动
            params_minus = params_array.copy()
            params_minus[i] -= epsilon
            model_minus = BergmanModel(BergmanParameters.from_array(params_minus))
            pred_minus = model_minus.predict(initial_state, time_points, meals, exercises)
            loss_minus = np.mean((pred_minus[:min_len] - glucose_history[:min_len]) ** 2)

            # 数值梯度
            gradients[i] = (loss_plus - loss_minus) / (2 * epsilon)

        # 梯度下降更新
        params_array = params_array - learning_rate * gradients

        # 确保参数在边界内
        lower, upper = current_params.bounds
        params_array = np.clip(params_array, lower, upper)

        return BergmanParameters.from_array(params_array)
