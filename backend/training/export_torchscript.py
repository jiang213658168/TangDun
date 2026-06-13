"""
将训练好的 TCN 模型导出为 TorchScript 格式，支持 Android 端增量训练

用法: python export_torchscript.py
输出: model_curve_v2_trainable.pt (TorchScript, 支持 forward + training)
"""
import torch
import torch.nn as nn
import numpy as np

# ============ 复用训练脚本中的模型定义 ============

class TCNModel(nn.Module):
    """TCN 模型 — 与 train_curve_v2.py 保持一致"""
    def __init__(self, input_dim=15, channels=[64, 64, 128], dropout=0.2):
        super().__init__()
        self.input_proj = nn.Sequential(
            nn.Linear(input_dim, channels[0]),
            nn.ReLU(),
            nn.Dropout(dropout),
        )
        # 简化的TCN层
        self.conv1 = nn.Conv1d(channels[0], channels[1], kernel_size=3, padding=1)
        self.conv2 = nn.Conv1d(channels[1], channels[2], kernel_size=3, padding=1)
        self.relu = nn.ReLU()
        self.dropout = nn.Dropout(dropout)
        self.output = nn.Linear(channels[2], 4)  # 输出 [a, b, c, d]

    def forward(self, x):
        # x: [batch, 15] 或 [batch, seq, 15]
        if x.dim() == 2:
            x = x.unsqueeze(1)  # [batch, 1, 15]
        embedded = self.input_proj(x)
        embedded = embedded.transpose(1, 2)  # [batch, ch, 1]
        h = self.relu(self.conv1(embedded))
        h = self.dropout(h)
        h = self.relu(self.conv2(h))
        h = torch.mean(h, dim=2)  # global average pooling
        return self.output(h)  # [batch, 4]


def export_trainable_model():
    """导出支持训练和推理的 TorchScript 模型"""

    # 1. 创建模型
    model = TCNModel(input_dim=15, channels=[64, 64, 128])
    model.train()  # 训练模式（保留 autograd）

    # 2. 尝试加载已训练的权重
    try:
        # 先尝试从 .pt 加载
        state = torch.load("data/model_curve_v2.pt", map_location="cpu")
        model.load_state_dict(state)
        print("✅ 已加载训练权重: model_curve_v2.pt")
    except:
        try:
            # 尝试从 training_history 加载最佳模型
            import json
            with open("data/training_history_curve_v2.json") as f:
                history = json.load(f)
            print(f"  训练历史: best_loss={min(history.get('val_loss', [999]))}")
            print("⚠ 未找到权重文件，使用随机初始化")
        except:
            print("⚠ 使用随机初始化的权重（需要训练）")

    # 3. TorchScript 导出（使用 torch.jit.trace）
    example_input = torch.randn(1, 15)

    # 先 trace 再 script（确保 autograd 保留）
    traced = torch.jit.trace(model, example_input)

    # 保存
    output_path = "../android/app/src/main/assets/model_curve_v2_trainable.pt"
    traced.save(output_path)
    print(f"✅ TorchScript模型已导出: {output_path}")

    # 4. 验证导出的模型
    loaded = torch.jit.load(output_path)
    loaded.eval()
    with torch.no_grad():
        test_input = torch.randn(1, 15)
        output = loaded(test_input)
        print(f"  验证: input[1,15] → output{list(output.shape)} = {output[0].tolist()}")
        print(f"  参数范围: [{output.min():.4f}, {output.max():.4f}]")

    return output_path


if __name__ == "__main__":
    export_trainable_model()
    print("\n完成！将 model_curve_v2_trainable.pt 放到 Android assets/ 目录")
