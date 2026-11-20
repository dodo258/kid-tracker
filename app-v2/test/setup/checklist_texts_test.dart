// 用相对路径引 lib/（原因见 legacy_data_test.dart 开头）
// ignore_for_file: avoid_relative_lib_imports
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import '../../lib/l10n/app_localizations.dart';
import '../../lib/setup/background_check.dart';
import '../../lib/setup/checklist_texts.dart';

void main() {
  const brands = ['huawei', 'honor', 'xiaomi', 'oppo', 'vivo', 'samsung', 'other'];

  List<EntryTexts> allTexts(AppLocalizations l, String brand) => [
        for (final item in CheckItem.values) autoTexts(l, item, brand),
        for (final item in VendorItem.values) vendorTexts(l, item, brand),
      ];

  test('简体、繁体：每个品牌每一项都有中文项目名、原因和菜单路径', () {
    for (final locale in [const Locale('zh'), const Locale.fromSubtags(languageCode: 'zh', countryCode: 'TW')]) {
      final l = lookupAppLocalizations(locale);
      for (final brand in brands) {
        for (final texts in allTexts(l, brand)) {
          for (final text in [texts.title, texts.why, texts.path]) {
            expect(text, isNotEmpty);
            // 除了 App、AI、Android、HyperOS 这类专有名词，不能是英文句子
            expect(text, matches(RegExp(r'[一-鿿]')), reason: '$locale $brand: $text');
          }
        }
      }
    }
  });

  test('品牌原话：同一项在不同品牌系统里叫法不同', () {
    final l = lookupAppLocalizations(const Locale('zh'));
    expect(vendorTexts(l, VendorItem.autostart, 'huawei').title, '允许自启动');
    expect(vendorTexts(l, VendorItem.autostart, 'xiaomi').title, '自启动');
    expect(vendorTexts(l, VendorItem.powerSaving, 'xiaomi').title, '省电策略「无限制」');
    expect(vendorTexts(l, VendorItem.powerSaving, 'vivo').title, '允许后台高耗电');
    expect(vendorTexts(l, VendorItem.powerSaving, 'oppo').title, '允许完全后台行为');
    expect(autoTexts(l, CheckItem.batteryOptimization, 'huawei').title, '电池优化「不允许」');
    expect(autoTexts(l, CheckItem.batteryOptimization, 'other').title, '电池用量「无限制」');
  });

  test('问 AI 的句子带机型和项目名', () {
    final l = lookupAppLocalizations(const Locale('zh'));
    expect(
      l.askAiPrompt('Redmi K70 / HyperOS OS2.0 / Android 15', '自启动'),
      '我的手机是Redmi K70 / HyperOS OS2.0 / Android 15，怎么给 App「定位服务」开启「自启动」',
    );
  });
}
