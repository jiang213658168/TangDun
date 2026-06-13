// flutter/lib/utils/validators.dart
// 表单验证工具

class Validators {
  // 血糖值验证
  static String? validateGlucose(String? value) {
    if (value == null || value.isEmpty) {
      return '请输入血糖值';
    }

    final glucose = double.tryParse(value);
    if (glucose == null) {
      return '请输入有效的数字';
    }

    if (glucose < 1.0 || glucose > 30.0) {
      return '血糖值应在1.0-30.0 mmol/L之间';
    }

    return null;
  }

  // 碳水量验证
  static String? validateCarbs(String? value) {
    if (value == null || value.isEmpty) {
      return '请输入碳水量';
    }

    final carbs = double.tryParse(value);
    if (carbs == null) {
      return '请输入有效的数字';
    }

    if (carbs < 0 || carbs > 500) {
      return '碳水量应在0-500g之间';
    }

    return null;
  }

  // 份量验证
  static String? validatePortion(String? value) {
    if (value == null || value.isEmpty) {
      return '请输入份量';
    }

    final portion = double.tryParse(value);
    if (portion == null) {
      return '请输入有效的数字';
    }

    if (portion <= 0 || portion > 5000) {
      return '份量应在0-5000g之间';
    }

    return null;
  }

  // 心率验证
  static String? validateHeartRate(String? value) {
    if (value == null || value.isEmpty) {
      return '请输入心率';
    }

    final hr = int.tryParse(value);
    if (hr == null) {
      return '请输入有效的整数';
    }

    if (hr < 30 || hr > 220) {
      return '心率应在30-220 bpm之间';
    }

    return null;
  }

  // 步数验证
  static String? validateSteps(String? value) {
    if (value == null || value.isEmpty) {
      return '请输入步数';
    }

    final steps = int.tryParse(value);
    if (steps == null) {
      return '请输入有效的整数';
    }

    if (steps < 0 || steps > 100000) {
      return '步数应在0-100000之间';
    }

    return null;
  }

  // 体重验证
  static String? validateWeight(String? value) {
    if (value == null || value.isEmpty) {
      return '请输入体重';
    }

    final weight = double.tryParse(value);
    if (weight == null) {
      return '请输入有效的数字';
    }

    if (weight < 20 || weight > 300) {
      return '体重应在20-300kg之间';
    }

    return null;
  }

  // 身高验证
  static String? validateHeight(String? value) {
    if (value == null || value.isEmpty) {
      return '请输入身高';
    }

    final height = double.tryParse(value);
    if (height == null) {
      return '请输入有效的数字';
    }

    if (height < 50 || height > 250) {
      return '身高应在50-250cm之间';
    }

    return null;
  }

  // 年龄验证
  static String? validateAge(String? value) {
    if (value == null || value.isEmpty) {
      return '请输入年龄';
    }

    final age = int.tryParse(value);
    if (age == null) {
      return '请输入有效的整数';
    }

    if (age < 1 || age > 150) {
      return '年龄应在1-150岁之间';
    }

    return null;
  }

  // 邮箱验证
  static String? validateEmail(String? value) {
    if (value == null || value.isEmpty) {
      return '请输入邮箱';
    }

    final emailRegex = RegExp(r'^[\w-\.]+@([\w-]+\.)+[\w-]{2,4}$');
    if (!emailRegex.hasMatch(value)) {
      return '请输入有效的邮箱地址';
    }

    return null;
  }

  // 手机号验证
  static String? validatePhone(String? value) {
    if (value == null || value.isEmpty) {
      return '请输入手机号';
    }

    final phoneRegex = RegExp(r'^1[3-9]\d{9}$');
    if (!phoneRegex.hasMatch(value)) {
      return '请输入有效的手机号';
    }

    return null;
  }

  // 非空验证
  static String? validateRequired(String? value, String fieldName) {
    if (value == null || value.isEmpty) {
      return '请输入$fieldName';
    }
    return null;
  }

  // 最小长度验证
  static String? validateMinLength(String? value, int minLength, String fieldName) {
    if (value == null || value.isEmpty) {
      return '请输入$fieldName';
    }

    if (value.length < minLength) {
      return '$fieldName至少需要$minLength个字符';
    }

    return null;
  }

  // 最大长度验证
  static String? validateMaxLength(String? value, int maxLength, String fieldName) {
    if (value != null && value.length > maxLength) {
      return '$fieldName不能超过$maxLength个字符';
    }
    return null;
  }
}
