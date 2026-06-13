# test_baidu_api.py
# 测试百度AI菜品识别API

import requests
import base64
import json
import sys

# ============ 配置 ============
API_KEY = "8FrGn0fkFjleEnUBY2c317j8"
SECRET_KEY = "7kS71cEhQcq54No6ZxQssNI7fXsor1Pc"

def get_access_token(api_key, secret_key):
    """获取access_token"""
    url = "https://aip.baidubce.com/oauth/2.0/token"
    data = {
        "grant_type": "client_credentials",
        "client_id": api_key,
        "client_secret": secret_key
    }
    response = requests.post(url, data=data)
    if response.status_code == 200:
        result = response.json()
        return result.get("access_token")
    else:
        print(f"获取token失败: {response.status_code}")
        print(response.text)
        return None

def recognize_dish(access_token, image_path):
    """菜品识别"""
    url = "https://aip.baidubce.com/rest/2.0/image-classify/v2/dish"

    # 读取图片并base64编码
    with open(image_path, "rb") as f:
        image_data = f.read()

    # base64编码（去掉编码头）
    image_base64 = base64.b64encode(image_data).decode("utf-8")

    # 构建请求
    # 注意：image需要urlencode
    data = {
        "image": image_base64,
        "top_num": 5
    }

    params = {
        "access_token": access_token
    }

    headers = {
        "Content-Type": "application/x-www-form-urlencoded"
    }

    print(f"\n请求URL: {url}")
    print(f"图片大小: {len(image_data) / 1024:.1f} KB")
    print(f"Base64长度: {len(image_base64)}")

    response = requests.post(url, params=params, data=data, headers=headers)

    print(f"\n响应状态码: {response.status_code}")

    if response.status_code == 200:
        result = response.json()
        print(f"\n响应内容:")
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return result
    else:
        print(f"请求失败: {response.text}")
        return None

def main():
    print("=" * 50)
    print("百度AI菜品识别API测试")
    print("=" * 50)

    # 检查配置
    if API_KEY == "YOUR_API_KEY":
        print("\n请先配置API_KEY和SECRET_KEY")
        print("在百度AI开放平台获取: https://ai.baidu.com/")
        return

    # 获取token
    print("\n1. 获取access_token...")
    token = get_access_token(API_KEY, SECRET_KEY)
    if not token:
        print("获取token失败!")
        return
    print(f"Token: {token[:20]}...")

    # 测试图片
    if len(sys.argv) > 1:
        image_path = sys.argv[1]
    else:
        print("\n请提供测试图片路径:")
        print("python test_baidu_api.py <图片路径>")
        return

    # 识别菜品
    print(f"\n2. 识别菜品: {image_path}")
    result = recognize_dish(token, image_path)

    if result and "result" in result:
        print("\n" + "=" * 50)
        print("识别结果:")
        print("=" * 50)
        for i, dish in enumerate(result["result"]):
            print(f"{i+1}. {dish['name']}")
            print(f"   置信度: {float(dish['probability'])*100:.1f}%")
            if dish.get("calorie"):
                print(f"   热量: {dish['calorie']} kcal/100g")
    else:
        print("\n识别失败!")

if __name__ == "__main__":
    main()
