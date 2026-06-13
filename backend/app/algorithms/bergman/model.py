# backend/app/algorithms/bergman/model.py
# 改良Bergman最小模型实现

import numpy as np
from scipy.integrate import odeint
from typing import List, Dict, Optional, Tuple
from app.algorithms.bergman.parameters import BergmanParameters


class BergmanModel:
    """改良Bergman最小模型

    基于经典Bergman最小模型(1979年)进行改良，加入饮食输入和运动消耗项

    微分方程组:
    1. 血糖动力学: dG/dt = -p1*(G-Gb) - X*G + D(t) - E(t)
    2. 胰岛素作用: dX/dt = -p2*X + p3*(I-Ib)
    3. 胰岛素分泌: dI/dt = -n*(I-Ib) + gamma*max(G-Gb,0) + U(t)
    """

    def __init__(self, params: Optional[BergmanParameters] = None):
        """初始化Bergman模型

        Args:
            params: 模型参数，如果为None则使用默认参数
        """
        self.params = params or BergmanParameters()

    def _diet_input(self, t: float, meals: List[Dict]) -> float:
        """饮食输入函数 D(t)

        使用双指数模型描述碳水化合物吸收动力学
        D(t) = sum[ carbs_i * k_abs * (exp(-k_abs*(t-t_i)) - exp(-k_fast*(t-t_i))) / (k_fast - k_abs) ]

        Args:
            t: 当前时间 (分钟)
            meals: 饮食事件列表，每项包含 'time' 和 'carbs'

        Returns:
            饮食输入速率 (mmol/L/min)
        """
        D = 0.0
        k_abs = self.params.k_abs
        k_fast = self.params.k_fast

        for meal in meals:
            t_i = meal.get('time', 0)
            carbs = meal.get('carbs', 0)  # 克

            if t >= t_i and carbs > 0:
                dt = t - t_i
                # 双指数吸收模型
                absorption = k_abs * (np.exp(-k_abs * dt) - np.exp(-k_fast * dt)) / (k_fast - k_abs)
                # 碳水转换为血糖 (考虑分布容积)
                # 每克碳水约产生5.56mmol葡萄糖，分布容积约0.16L/kg，假设70kg体重
                glucose_per_carb = 5.56 / (self.params.Vg * 70)
                D += carbs * glucose_per_carb * absorption

        return D

    def _exercise_input(self, t: float, exercises: List[Dict]) -> float:
        """运动消耗函数 E(t)

        包含运动中消耗和运动后过量氧耗(EPOC)两部分

        Args:
            t: 当前时间 (分钟)
            exercises: 运动事件列表，每项包含 'start_time', 'duration', 'met'

        Returns:
            运动消耗速率 (mmol/L/min)
        """
        E = 0.0

        for exercise in exercises:
            start_time = exercise.get('start_time', 0)
            duration = exercise.get('duration', 30)  # 分钟
            met = exercise.get('met', 3.0)  # 代谢当量

            end_time = start_time + duration

            if t < start_time:
                continue

            if t <= end_time:
                # 运动中消耗
                E += met * 0.001
            else:
                # 运动后EPOC效应
                post_dt = t - end_time
                epoc = (0.3 * np.exp(-post_dt / 30) + 0.7 * np.exp(-post_dt / 120)) * met * 0.001
                E += epoc

                # 运动后胰岛素敏感性增加效应
                sensitivity_increase = 0.0005 * met * np.exp(-post_dt / 240)
                E += sensitivity_increase

        return E

    def _ode_system(self, y: List[float], t: float, meals: List[Dict],
                    exercises: List[Dict], insulin_input: float) -> List[float]:
        """ODE系统定义

        Args:
            y: 状态向量 [G, X, I]
            t: 当前时间
            meals: 饮食事件
            exercises: 运动事件
            insulin_input: 外源胰岛素输入

        Returns:
            状态导数 [dG/dt, dX/dt, dI/dt]
        """
        G, X, I = y
        p1 = self.params.p1
        p2 = self.params.p2
        p3 = self.params.p3
        n = self.params.n
        gamma = self.params.gamma
        Gb = self.params.Gb
        Ib = self.params.Ib

        # 饮食输入
        D = self._diet_input(t, meals)

        # 运动消耗
        E = self._exercise_input(t, exercises)

        # 血糖动力学方程
        dG_dt = -p1 * (G - Gb) - X * G + D - E

        # 胰岛素作用动力学方程
        dX_dt = -p2 * X + p3 * (I - Ib)

        # 胰岛素分泌动力学方程
        dI_dt = -n * (I - Ib) + gamma * max(G - Gb, 0) + insulin_input

        return [dG_dt, dX_dt, dI_dt]

    def predict(self, initial_state: Tuple[float, float, float],
                time_points: np.ndarray,
                meals: List[Dict] = None,
                exercises: List[Dict] = None,
                insulin_input: float = 0) -> np.ndarray:
        """预测血糖变化

        Args:
            initial_state: 初始状态 (G0, X0, I0)
            time_points: 时间点数组 (分钟)
            meals: 饮食事件列表
            exercises: 运动事件列表
            insulin_input: 外源胰岛素输入

        Returns:
            血糖值数组
        """
        meals = meals or []
        exercises = exercises or []

        # 求解ODE
        solution = odeint(
            self._ode_system,
            initial_state,
            time_points,
            args=(meals, exercises, insulin_input)
        )

        return solution[:, 0]  # 返回血糖值

    def predict_with_confidence(self, initial_state: Tuple[float, float, float],
                                 time_points: np.ndarray,
                                 meals: List[Dict] = None,
                                 exercises: List[Dict] = None,
                                 n_samples: int = 100) -> Dict:
        """带置信区间的预测

        Args:
            initial_state: 初始状态
            time_points: 时间点数组
            meals: 饮食事件
            exercises: 运动事件
            n_samples: 蒙特卡洛采样数

        Returns:
            包含预测值和置信区间的字典
        """
        # 基准预测
        base_prediction = self.predict(initial_state, time_points, meals, exercises)

        # 蒙特卡洛采样估计不确定性
        samples = []
        for _ in range(n_samples):
            # 添加参数扰动
            noise = np.random.normal(0, 0.05, 5)
            perturbed_params = BergmanParameters.from_array(
                self.params.to_array() * (1 + noise)
            )
            perturbed_model = BergmanModel(perturbed_params)
            sample = perturbed_model.predict(initial_state, time_points, meals, exercises)
            samples.append(sample)

        samples = np.array(samples)

        # 计算置信区间
        lower = np.percentile(samples, 2.5, axis=0)
        upper = np.percentile(samples, 97.5, axis=0)

        return {
            'prediction': base_prediction,
            'lower': lower,
            'upper': upper,
            'std': np.std(samples, axis=0)
        }

    def calculate_risk_level(self, glucose_value: float) -> str:
        """计算血糖风险等级

        Args:
            glucose_value: 血糖值 (mmol/L)

        Returns:
            风险等级: normal/low_risk/high_risk
        """
        if glucose_value < 3.9:
            return "low_risk"
        elif glucose_value > 10.0:
            return "high_risk"
        else:
            return "normal"

    def what_if_simulation(self, current_glucose: float,
                            current_insulin: float,
                            meal_plan: List[Dict],
                            exercise_plan: List[Dict] = None,
                            horizon: int = 120) -> Dict:
        """What-if模拟

        Args:
            current_glucose: 当前血糖值
            current_insulin: 当前胰岛素水平
            meal_plan: 计划饮食
            exercise_plan: 计划运动
            horizon: 预测时域(分钟)

        Returns:
            模拟结果
        """
        # 初始状态
        initial_state = (current_glucose, 0, current_insulin)

        # 时间点
        time_points = np.arange(0, horizon + 1, 5)

        # 预测
        prediction = self.predict(initial_state, time_points, meal_plan, exercise_plan)

        # 计算峰值和达峰时间
        peak_idx = np.argmax(prediction)
        peak_value = prediction[peak_idx]
        peak_time = time_points[peak_idx]

        # 计算风险等级
        risk_level = self.calculate_risk_level(peak_value)

        return {
            'glucose_curve': prediction.tolist(),
            'time_points': time_points.tolist(),
            'peak_value': float(peak_value),
            'peak_time': int(peak_time),
            'risk_level': risk_level,
            'initial_glucose': current_glucose,
            'final_glucose': float(prediction[-1])
        }
