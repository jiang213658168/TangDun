# backend/training/process_ohio_hupa.py
# 处理OhioT1DM + HUPA数据集（都有完整字段）

import zipfile
import csv
import os
import sys
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def process_ohio(zip_path: str) -> list:
    """处理OhioT1DM数据集"""
    print("[1/2] 处理OhioT1DM数据集...")
    records = []

    with zipfile.ZipFile(zip_path, 'r') as z:
        csv_files = [f for f in z.namelist() if f.endswith('_processed.csv')]

        for filepath in csv_files:
            patient_id = filepath.split('/')[-1].replace('_processed.csv', '')

            with z.open(filepath) as f:
                content = f.read().decode('utf-8')
                lines = content.strip().split('\n')

                for line in lines[1:]:
                    fields = line.split(',')
                    if len(fields) >= 9:
                        try:
                            glucose_mgdl = float(fields[2]) if fields[2] else None
                            basal = float(fields[4]) if fields[4] else None
                            bolus = float(fields[8]) if fields[8] else None
                            carbs = float(fields[7]) if fields[7] else None
                            hr = float(fields[5]) if fields[5] else None

                            if glucose_mgdl and glucose_mgdl > 0:
                                glucose_mmol = round(glucose_mgdl / 18.0182, 2)
                                if 2.0 <= glucose_mmol <= 30.0:
                                    records.append({
                                        'glucose': glucose_mmol,
                                        'carbs': carbs if carbs else 0,
                                        'bolus': bolus if bolus else 0,
                                        'basal_rate': basal if basal else 0,
                                        'heart_rate': hr if hr else 0,
                                        'steps': 0,
                                        'patient_id': f'Ohio_{patient_id}',
                                    })
                        except (ValueError, IndexError):
                            continue

    patients = set(r['patient_id'] for r in records)
    print(f"  患者: {len(patients)}人, 记录: {len(records):,}条")
    return records


def process_hupa(zip_path: str) -> list:
    """处理HUPA数据集"""
    print("[2/2] 处理HUPA数据集...")
    records = []

    with zipfile.ZipFile(zip_path, 'r') as z:
        csv_files = [f for f in z.namelist() if 'Preprocessed' in f and f.endswith('.csv')]

        for filepath in csv_files:
            patient_id = filepath.split('/')[-1].replace('.csv', '')

            with z.open(filepath) as f:
                content = f.read().decode('utf-8')
                lines = content.strip().split('\n')

                for line in lines[1:]:
                    fields = line.split(';')
                    if len(fields) >= 8:
                        try:
                            glucose_mgdl = float(fields[1]) if fields[1] else None
                            if glucose_mgdl and glucose_mgdl > 0:
                                glucose_mmol = round(glucose_mgdl / 18.0182, 2)
                                if 2.0 <= glucose_mmol <= 30.0:
                                    records.append({
                                        'glucose': glucose_mmol,
                                        'carbs': float(fields[7]) if fields[7] else 0,
                                        'bolus': float(fields[6]) if fields[6] else 0,
                                        'basal_rate': float(fields[5]) if fields[5] else 0,
                                        'heart_rate': float(fields[3]) if fields[3] else 0,
                                        'steps': float(fields[4]) if fields[4] else 0,
                                        'patient_id': f'HUPA_{patient_id}',
                                    })
                        except (ValueError, IndexError):
                            continue

    patients = set(r['patient_id'] for r in records)
    print(f"  患者: {len(patients)}人, 记录: {len(records):,}条")
    return records


def main():
    data_dir = os.path.join(os.path.dirname(__file__), 'data')
    os.makedirs(data_dir, exist_ok=True)

    print("=" * 60)
    print("处理OhioT1DM + HUPA数据集")
    print("=" * 60)
    print("特点: 都有完整字段（CGM+胰岛素+碳水）")
    print()

    all_records = []

    # 处理OhioT1DM
    ohio_path = r'D:\数据集\ohiot1dm-glucose-dataset-main.zip'
    if os.path.exists(ohio_path):
        all_records.extend(process_ohio(ohio_path))
    else:
        print(f"  文件不存在: {ohio_path}")

    # 处理HUPA
    hupa_path = r'D:\BaiduNetdiskDownload\HUPA-UCM Diabetes Dataset.zip'
    if os.path.exists(hupa_path):
        all_records.extend(process_hupa(hupa_path))
    else:
        print(f"  文件不存在: {hupa_path}")

    # 统计
    patients = set(r['patient_id'] for r in all_records)
    glucose_values = [r['glucose'] for r in all_records]
    insulin_records = [r for r in all_records if r['bolus'] > 0 or r['basal_rate'] > 0]
    carb_records = [r for r in all_records if r['carbs'] > 0]

    print(f"\n{'='*60}")
    print("合并数据统计")
    print(f"{'='*60}")
    print(f"总患者: {len(patients)}")
    print(f"总记录: {len(all_records):,}")
    print(f"平均血糖: {np.mean(glucose_values):.2f} mmol/L")
    print(f"血糖范围: {np.min(glucose_values):.2f} - {np.max(glucose_values):.2f} mmol/L")
    print(f"有胰岛素记录: {len(insulin_records):,}条 ({len(insulin_records)/len(all_records)*100:.1f}%)")
    print(f"有碳水记录: {len(carb_records):,}条 ({len(carb_records)/len(all_records)*100:.1f}%)")

    # 按来源统计
    sources = {}
    for r in all_records:
        src = r['patient_id'].split('_')[0]
        if src not in sources:
            sources[src] = {'patients': set(), 'records': 0}
        sources[src]['patients'].add(r['patient_id'])
        sources[src]['records'] += 1

    print("\n各数据集:")
    for src, info in sources.items():
        print(f"  {src}: {len(info['patients'])}人, {info['records']:,}条")

    # 保存
    output_file = os.path.join(data_dir, 'ohio_hupa_data.csv')
    with open(output_file, 'w', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=[
            'glucose', 'carbs', 'bolus', 'basal_rate', 'heart_rate', 'steps', 'patient_id'
        ])
        writer.writeheader()
        for record in all_records:
            writer.writerow(record)

    print(f"\n输出: {output_file}")
    print(f"大小: {os.path.getsize(output_file) / 1024 / 1024:.1f} MB")


if __name__ == '__main__':
    main()
