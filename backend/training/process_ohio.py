# backend/training/process_ohio.py
# 处理OhioT1DM数据集

import zipfile
import csv
import os
import sys
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def process_ohio_dataset(zip_path: str, output_dir: str):
    """处理OhioT1DM数据集

    字段:
    - cbg: CGM血糖值 (mg/dL)
    - basal: 基础胰岛素
    - bolus: 大剂量胰岛素
    - carbInput: 碳水摄入
    - hr: 心率
    """
    print("=" * 60)
    print("处理OhioT1DM数据集")
    print("=" * 60)

    os.makedirs(output_dir, exist_ok=True)

    all_records = []

    with zipfile.ZipFile(zip_path, 'r') as z:
        csv_files = [f for f in z.namelist() if f.endswith('_processed.csv')]

        for filepath in csv_files:
            patient_id = filepath.split('/')[-1].replace('_processed.csv', '')

            with z.open(filepath) as f:
                content = f.read().decode('utf-8')
                lines = content.strip().split('\n')

                # 跳过表头
                for line in lines[1:]:
                    fields = line.split(',')
                    if len(fields) >= 9:
                        try:
                            # 解析字段
                            glucose_mgdl = float(fields[2]) if fields[2] else None
                            basal = float(fields[4]) if fields[4] else None
                            bolus = float(fields[8]) if fields[8] else None
                            carbs = float(fields[7]) if fields[7] else None
                            hr = float(fields[5]) if fields[5] else None

                            if glucose_mgdl and glucose_mgdl > 0:
                                # 转换为mmol/L
                                glucose_mmol = round(glucose_mgdl / 18.0182, 2)

                                if 2.0 <= glucose_mmol <= 30.0:
                                    record = {
                                        'glucose': glucose_mmol,
                                        'carbs': carbs if carbs else 0,
                                        'bolus': bolus if bolus else 0,
                                        'basal_rate': basal if basal else 0,
                                        'heart_rate': hr if hr else 0,
                                        'steps': 0,
                                        'patient_id': f'Ohio_{patient_id}',
                                    }
                                    all_records.append(record)
                        except (ValueError, IndexError):
                            continue

    # 统计
    patients = set(r['patient_id'] for r in all_records)
    glucose_values = [r['glucose'] for r in all_records]
    insulin_records = [r for r in all_records if r['bolus'] > 0 or r['basal_rate'] > 0]
    carb_records = [r for r in all_records if r['carbs'] > 0]

    print(f"\n数据统计:")
    print(f"  患者数: {len(patients)}")
    print(f"  总记录: {len(all_records):,}")
    print(f"  平均血糖: {np.mean(glucose_values):.2f} mmol/L")
    print(f"  血糖范围: {np.min(glucose_values):.2f} - {np.max(glucose_values):.2f} mmol/L")
    print(f"  有胰岛素记录: {len(insulin_records):,}条")
    print(f"  有碳水记录: {len(carb_records):,}条")

    # 保存
    output_file = os.path.join(output_dir, 'ohio_data.csv')
    with open(output_file, 'w', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=[
            'glucose', 'carbs', 'bolus', 'basal_rate', 'heart_rate', 'steps', 'patient_id'
        ])
        writer.writeheader()
        for record in all_records:
            writer.writerow(record)

    print(f"\n输出: {output_file}")
    print(f"大小: {os.path.getsize(output_file) / 1024 / 1024:.1f} MB")

    return output_file


if __name__ == '__main__':
    zip_path = r'D:\数据集\ohiot1dm-glucose-dataset-main.zip'
    output_dir = os.path.join(os.path.dirname(__file__), 'data')
    process_ohio_dataset(zip_path, output_dir)
