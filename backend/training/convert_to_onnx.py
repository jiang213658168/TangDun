# backend/training/convert_to_onnx.py
# 将TCN模型转换为ONNX格式（用于Android部署）

import torch
import os
import sys

# 添加项目路径
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from training.train_curve_v2 import TCNCurvePredictor


def convert_to_onnx():
    """将PyTorch模型转换为ONNX格式"""

    data_dir = os.path.join(os.path.dirname(__file__), 'data')
    model_path = os.path.join(data_dir, 'model_curve_v2.pt')
    onnx_path = os.path.join(data_dir, 'model_curve_v2.onnx')

    print("=" * 60)
    print("TCN模型转换: PyTorch → ONNX")
    print("=" * 60)

    # 检查模型文件
    if not os.path.exists(model_path):
        print(f"错误: 模型文件不存在: {model_path}")
        return False

    # 加载PyTorch模型
    print(f"\n加载模型: {model_path}")
    checkpoint = torch.load(model_path, map_location='cpu', weights_only=False)

    input_dim = checkpoint.get('input_dim', 15)
    channels = checkpoint.get('channels', [64, 64, 128])

    print(f"  输入维度: {input_dim}")
    print(f"  通道数: {channels}")

    # 创建模型
    model = TCNCurvePredictor(
        input_dim=input_dim,
        channels=channels,
        dropout=0.0  # 推理时不用dropout
    )
    model.load_state_dict(checkpoint['model_state_dict'])
    model.eval()

    # 创建dummy输入
    dummy_input = torch.randn(1, input_dim)
    print(f"\nDummy输入形状: {dummy_input.shape}")

    # 转换为ONNX
    print(f"\n转换为ONNX...")
    torch.onnx.export(
        model,
        dummy_input,
        onnx_path,
        export_params=True,
        opset_version=11,
        do_constant_folding=True,
        input_names=['input'],
        output_names=['output'],
        dynamic_axes={
            'input': {0: 'batch_size'},
            'output': {0: 'batch_size'}
        }
    )

    # 验证ONNX模型
    print(f"\n验证ONNX模型...")
    try:
        import onnxruntime as ort

        # 加载ONNX模型
        session = ort.InferenceSession(onnx_path)

        # 推理测试
        import numpy as np
        test_input = np.random.randn(1, input_dim).astype(np.float32)
        result = session.run(None, {'input': test_input})

        print(f"  输出形状: {result[0].shape}")
        print(f"  输出值: {result[0][0]}")

        # 文件大小
        file_size = os.path.getsize(onnx_path) / 1024
        print(f"\n✓ 转换成功!")
        print(f"  输出文件: {onnx_path}")
        print(f"  文件大小: {file_size:.1f} KB")

        return True

    except ImportError:
        print("\n警告: onnxruntime未安装，跳过验证")
        print("安装命令: pip install onnxruntime")

        file_size = os.path.getsize(onnx_path) / 1024
        print(f"\n✓ 转换完成!")
        print(f"  输出文件: {onnx_path}")
        print(f"  文件大小: {file_size:.1f} KB")

        return True

    except Exception as e:
        print(f"\n✗ 验证失败: {e}")
        return False


def copy_to_android():
    """复制ONNX模型到Android assets目录"""

    source = os.path.join(os.path.dirname(__file__), 'data', 'model_curve_v2.onnx')
    target_dir = os.path.join(os.path.dirname(__file__), '..', '..', 'android', 'app', 'src', 'main', 'assets')
    target = os.path.join(target_dir, 'model_curve_v2.onnx')

    if not os.path.exists(source):
        print(f"错误: ONNX模型不存在: {source}")
        print("请先运行转换: python training/convert_to_onnx.py")
        return False

    os.makedirs(target_dir, exist_ok=True)

    import shutil
    shutil.copy2(source, target)

    print(f"✓ 已复制到Android项目: {target}")
    return True


if __name__ == '__main__':
    import argparse

    parser = argparse.ArgumentParser(description='TCN模型转换工具')
    parser.add_argument('--copy', action='store_true', help='复制到Android项目')
    args = parser.parse_args()

    if args.copy:
        copy_to_android()
    else:
        success = convert_to_onnx()
        if success:
            print("\n下一步: 运行 --copy 复制到Android项目")
            print("  python training/convert_to_onnx.py --copy")
