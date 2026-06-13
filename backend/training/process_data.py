# backend/training/process_data.py
# 处理所有真实CGM数据集（排除模拟数据和非糖尿病数据）

import zipfile
import gzip
import csv
import os
import sys
import numpy as np
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def convert_to_mmol(value_mgdl):
    """mg/dL -> mmol/L"""
    return round(value_mgdl / 18.0182, 2)


def is_valid_glucose(value_mmol):
    """检查血糖值是否在合理范围内"""
    return 2.0 <= value_mmol <= 30.0


def process_hupa():
    """处理HUPA-UCM数据集（有胰岛素+碳水+CGM）"""
    print("[1/5] 处理HUPA-UCM数据集...")
    records = []
    zip_path = r'D:\BaiduNetdiskDownload\HUPA-UCM Diabetes Dataset.zip'

    if not os.path.exists(zip_path):
        print(f"  文件不存在: {zip_path}")
        return records

    with zipfile.ZipFile(zip_path, 'r') as z:
        csv_files = [f for f in z.namelist() if 'Preprocessed' in f and f.endswith('.csv')]
        for filepath in csv_files:
            pid = filepath.split('/')[-1].replace('.csv', '')
            with z.open(filepath) as f:
                content = f.read().decode('utf-8')
                lines = content.strip().split('\n')
                for line in lines[1:]:
                    fields = line.split(';')
                    if len(fields) >= 8:
                        try:
                            glucose_mgdl = float(fields[1]) if fields[1] else None
                            if glucose_mgdl and glucose_mgdl > 0:
                                glucose_mmol = convert_to_mmol(glucose_mgdl)
                                if is_valid_glucose(glucose_mmol):
                                    records.append({
                                        'glucose': glucose_mmol,
                                        'carbs': float(fields[7]) if fields[7] else 0,
                                        'bolus': float(fields[6]) if fields[6] else 0,
                                        'basal_rate': float(fields[5]) if fields[5] else 0,
                                        'patient_id': f'HUPA_{pid}',
                                    })
                        except (ValueError, IndexError):
                            continue

    print(f"  患者: {len(set(r['patient_id'] for r in records))}人, 记录: {len(records)}条")
    return records


def process_rt_cgm():
    """处理RT_CGM数据集（糖尿病患者CGM数据）"""
    print("[2/5] 处理RT_CGM数据集...")
    records = []
    zip_path = r'D:\数据集\RT_CGM_Randomized_Clinical_Trial.zip'

    if not os.path.exists(zip_path):
        print(f"  文件不存在: {zip_path}")
        return records

    with zipfile.ZipFile(zip_path, 'r') as z:
        cgm_files = [f for f in z.namelist() if 'DataRTCGM' in f and f.endswith('.csv')]
        print(f"  文件数: {len(cgm_files)}")

        for file_idx, filepath in enumerate(cgm_files):
            pid = filepath.split('/')[-1].replace('.csv', '')
            file_records = 0

            with z.open(filepath) as f:
                content = f.read().decode('utf-8-sig')
                lines = content.strip().split('\n')

                for line in lines[1:]:
                    # CSV格式: "RecID","PtID","DeviceDtTm","Glucose"
                    clean_line = line.replace('"', '')
                    fields = clean_line.split(',')

                    if len(fields) >= 4:
                        try:
                            pt_id = fields[1]
                            glucose_mgdl = float(fields[3])
                            if glucose_mgdl > 0:
                                glucose_mmol = convert_to_mmol(glucose_mgdl)
                                if is_valid_glucose(glucose_mmol):
                                    records.append({
                                        'glucose': glucose_mmol,
                                        'carbs': 0,
                                        'bolus': 0,
                                        'basal_rate': 0,
                                        'patient_id': f'RTCGM_{pt_id}',
                                    })
                                    file_records += 1
                        except (ValueError, IndexError):
                            continue

            if file_idx % 5 == 0:
                print(f"  [{file_idx+1}/{len(cgm_files)}] 已处理 {file_records:,}条")

    print(f"  总患者数: {len(set(r['patient_id'] for r in records))}")
    print(f"  总记录数: {len(records):,}")
    return records


def process_direcnet():
    """处理DirecNet数据集"""
    print("[3/5] 处理DirecNet数据集...")
    records = []

    # DirecNetOupatient
    zip_path = r'D:\数据集\DirecNetOupatientRandomizedClinicalTrial.zip'
    if os.path.exists(zip_path):
        with zipfile.ZipFile(zip_path, 'r') as z:
            cgm_files = [f for f in z.namelist() if 'DataCGMS' in f and f.endswith('.csv')]
            for filepath in cgm_files:
                with z.open(filepath) as f:
                    content = f.read().decode('utf-8-sig')
                    lines = content.strip().split('\n')
                    for line in lines[1:]:
                        fields = line.split(',')
                        if len(fields) >= 6:
                            try:
                                pt_id = fields[1].strip('"')
                                sensor_glu = fields[5].strip('"')
                                if sensor_glu:
                                    glucose_mgdl = float(sensor_glu)
                                    if glucose_mgdl > 0:
                                        glucose_mmol = convert_to_mmol(glucose_mgdl)
                                        if is_valid_glucose(glucose_mmol):
                                            records.append({
                                                'glucose': glucose_mmol,
                                                'carbs': 0,
                                                'bolus': 0,
                                                'basal_rate': 0,
                                                'patient_id': f'DNO_{pt_id}',
                                            })
                            except (ValueError, IndexError):
                                continue

    # DirecNetInPtExercise
    zip_path = r'D:\数据集\DirecNetInPtExercise.zip'
    if os.path.exists(zip_path):
        with zipfile.ZipFile(zip_path, 'r') as z:
            with z.open('DirecNetInPtExercise/DataTables/tblDDataCGMS.csv') as f:
                content = f.read().decode('utf-8-sig')
                lines = content.strip().split('\n')
                for line in lines[1:]:
                    fields = line.split(',')
                    if len(fields) >= 6:
                        try:
                            pt_id = fields[1].strip('"')
                            sensor_glu = fields[5].strip('"')
                            if sensor_glu:
                                glucose_mgdl = float(sensor_glu)
                                if glucose_mgdl > 0:
                                    glucose_mmol = convert_to_mmol(glucose_mgdl)
                                    if is_valid_glucose(glucose_mmol):
                                        records.append({
                                            'glucose': glucose_mmol,
                                            'carbs': 0,
                                            'bolus': 0,
                                            'basal_rate': 0,
                                            'patient_id': f'DNE_{pt_id}',
                                        })
                        except (ValueError, IndexError):
                            continue

    print(f"  患者数: {len(set(r['patient_id'] for r in records))}")
    print(f"  记录数: {len(records)}")
    return records


def process_pone():
    """处理pone数据集"""
    print("[4/5] 处理pone数据集...")
    records = []
    zip_path = r'D:\数据集\pone.0225817.s001.zip'

    if not os.path.exists(zip_path):
        return records

    with zipfile.ZipFile(zip_path, 'r') as z:
        csv_files = [f for f in z.namelist() if f.endswith('.csv')]

        for filepath in csv_files:
            pid = filepath.split('/')[-1].replace('.csv', '').strip()
            base_time = datetime(2024, 1, 1)

            with z.open(filepath) as f:
                content = f.read().decode('utf-8-sig')
                lines = content.strip().split('\n')

                for i, line in enumerate(lines[1:]):
                    fields = line.split(',')
                    if len(fields) >= 3:
                        try:
                            glucose_mgdl = float(fields[2].strip('"'))
                            glucose_mmol = convert_to_mmol(glucose_mgdl)
                            if is_valid_glucose(glucose_mmol):
                                records.append({
                                    'glucose': glucose_mmol,
                                    'carbs': 0,
                                    'bolus': 0,
                                    'basal_rate': 0,
                                    'patient_id': f'PONE_{pid}',
                                })
                        except (ValueError, IndexError):
                            continue

    print(f"  患者数: {len(set(r['patient_id'] for r in records))}")
    print(f"  记录数: {len(records)}")
    return records


def process_pbio():
    """处理pbio数据集"""
    print("[5/5] 处理pbio数据集...")
    records = []
    gz_path = r'D:\数据集\pbio.2005143.s010.gz'

    if not os.path.exists(gz_path):
        return records

    with gzip.open(gz_path, 'rt') as f:
        lines = f.readlines()

    for line in lines[1:]:
        fields = line.strip().split('\t')
        if len(fields) >= 3:
            try:
                glucose_mgdl = float(fields[1])
                if glucose_mgdl > 0:
                    glucose_mmol = convert_to_mmol(glucose_mgdl)
                    if is_valid_glucose(glucose_mmol):
                        records.append({
                            'glucose': glucose_mmol,
                            'carbs': 0,
                            'bolus': 0,
                            'basal_rate': 0,
                            'patient_id': f'PBIO_{fields[2]}',
                        })
            except (ValueError, IndexError):
                continue

    print(f"  患者数: {len(set(r['patient_id'] for r in records))}")
    print(f"  记录数: {len(records)}")
    return records


def main():
    data_dir = os.path.join(os.path.dirname(__file__), 'data')
    os.makedirs(data_dir, exist_ok=True)

    print("=" * 70)
    print("处理所有真实CGM数据集")
    print("=" * 70)
    print()
    print("排除:")
    print("  - CGMND (非糖尿病患者)")
    print("  - simglucose (模拟数据)")
    print("  - CTR3/IOBP2/PEDAP (无CSV数据)")
    print("  - iglu (R包，非数据)")
    print()

    all_records = []
    all_records.extend(process_hupa())
    all_records.extend(process_rt_cgm())
    all_records.extend(process_direcnet())
    all_records.extend(process_pone())
    all_records.extend(process_pbio())

    # 统计
    patients = set(r['patient_id'] for r in all_records)
    glucose_values = [r['glucose'] for r in all_records]

    print(f"\n{'='*70}")
    print("数据统计")
    print(f"{'='*70}")
    print(f"总患者: {len(patients)}")
    print(f"总记录: {len(glucose_values):,}")
    print(f"平均血糖: {np.mean(glucose_values):.2f} mmol/L")
    print(f"标准差: {np.std(glucose_values):.2f} mmol/L")
    print(f"最小值: {np.min(glucose_values):.2f} mmol/L")
    print(f"最大值: {np.max(glucose_values):.2f} mmol/L")

    # 按来源统计
    sources = {}
    for r in all_records:
        src = r['patient_id'].split('_')[0]
        if src not in sources:
            sources[src] = {'patients': set(), 'records': 0}
        sources[src]['patients'].add(r['patient_id'])
        sources[src]['records'] += 1

    print("\n各数据集:")
    for src, info in sorted(sources.items(), key=lambda x: -x[1]['records']):
        print(f"  {src}: {len(info['patients'])}人, {info['records']:,}条")

    # 保存
    output_file = os.path.join(data_dir, 'real_cgm_data.csv')
    with open(output_file, 'w', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=['glucose', 'carbs', 'bolus', 'basal_rate', 'patient_id'])
        writer.writeheader()
        for record in all_records:
            writer.writerow(record)

    print(f"\n输出: {output_file}")
    print(f"大小: {os.path.getsize(output_file) / 1024 / 1024:.1f} MB")


if __name__ == '__main__':
    main()
