# backend/app/algorithms/transformer_lstm/attention.py
# 多头注意力机制实现

import numpy as np
from typing import Tuple, Optional


class MultiHeadAttention:
    """多头注意力机制

    实现Transformer编码器中的多头自注意力机制
    """

    def __init__(self, d_model: int, num_heads: int):
        """初始化多头注意力

        Args:
            d_model: 模型维度
            num_heads: 注意力头数
        """
        self.d_model = d_model
        self.num_heads = num_heads
        self.d_k = d_model // num_heads

        # 初始化权重矩阵
        scale = np.sqrt(2.0 / d_model)
        self.W_q = np.random.randn(d_model, d_model) * scale
        self.W_k = np.random.randn(d_model, d_model) * scale
        self.W_v = np.random.randn(d_model, d_model) * scale
        self.W_o = np.random.randn(d_model, d_model) * scale

    def _split_heads(self, x: np.ndarray) -> np.ndarray:
        """将输入分割为多个头

        Args:
            x: 输入张量 (batch_size, seq_len, d_model)

        Returns:
            分割后的张量 (batch_size, num_heads, seq_len, d_k)
        """
        batch_size, seq_len, _ = x.shape
        x = x.reshape(batch_size, seq_len, self.num_heads, self.d_k)
        x = x.transpose(0, 2, 1, 3)
        return x

    def _concat_heads(self, x: np.ndarray) -> np.ndarray:
        """将多个头的输出拼接

        Args:
            x: 多头输出 (batch_size, num_heads, seq_len, d_k)

        Returns:
            拼接后的张量 (batch_size, seq_len, d_model)
        """
        batch_size, _, seq_len, _ = x.shape
        x = x.transpose(0, 2, 1, 3)
        x = x.reshape(batch_size, seq_len, self.d_model)
        return x

    def _scaled_dot_product_attention(self, Q: np.ndarray, K: np.ndarray,
                                       V: np.ndarray, mask: Optional[np.ndarray] = None) -> Tuple[np.ndarray, np.ndarray]:
        """缩放点积注意力

        Args:
            Q: 查询矩阵
            K: 键矩阵
            V: 值矩阵
            mask: 掩码矩阵

        Returns:
            (注意力输出, 注意力权重)
        """
        # 计算注意力分数
        scores = np.matmul(Q, K.transpose(0, 1, 3, 2)) / np.sqrt(self.d_k)

        # 应用掩码
        if mask is not None:
            scores = scores + mask * -1e9

        # Softmax
        attention_weights = self._softmax(scores)

        # 加权求和
        output = np.matmul(attention_weights, V)

        return output, attention_weights

    def _softmax(self, x: np.ndarray) -> np.ndarray:
        """Softmax函数

        Args:
            x: 输入数组

        Returns:
            Softmax输出
        """
        exp_x = np.exp(x - np.max(x, axis=-1, keepdims=True))
        return exp_x / np.sum(exp_x, axis=-1, keepdims=True)

    def forward(self, x: np.ndarray, mask: Optional[np.ndarray] = None) -> Tuple[np.ndarray, np.ndarray]:
        """前向传播

        Args:
            x: 输入张量 (batch_size, seq_len, d_model)
            mask: 掩码矩阵

        Returns:
            (输出张量, 注意力权重)
        """
        # 线性变换
        Q = np.matmul(x, self.W_q)
        K = np.matmul(x, self.W_k)
        V = np.matmul(x, self.W_v)

        # 分割头
        Q = self._split_heads(Q)
        K = self._split_heads(K)
        V = self._split_heads(V)

        # 计算注意力
        attn_output, attn_weights = self._scaled_dot_product_attention(Q, K, V, mask)

        # 拼接头
        output = self._concat_heads(attn_output)

        # 输出线性变换
        output = np.matmul(output, self.W_o)

        return output, attn_weights

    def get_attention_weights(self, x: np.ndarray) -> np.ndarray:
        """获取注意力权重 (用于可解释性)

        Args:
            x: 输入张量

        Returns:
            注意力权重
        """
        _, attn_weights = self.forward(x)

        # 平均所有头的注意力权重
        avg_weights = np.mean(attn_weights, axis=1)

        return avg_weights
