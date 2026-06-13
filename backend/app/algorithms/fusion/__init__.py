# backend/app/algorithms/fusion/__init__.py
# 模型融合模块包初始化

from app.algorithms.fusion.bma import BayesianModelAveraging

__all__ = ["BayesianModelAveraging"]
