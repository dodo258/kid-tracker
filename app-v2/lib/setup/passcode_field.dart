import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../l10n/app_localizations.dart';

/// 提交一次后的反馈：[message] 换到提示行，输入清空。[error] 红字并抖动、震一下；[disabled] 停用键盘（即将退出）。
class EntryFeedback {
  const EntryFeedback(this.message, {this.error = true, this.disabled = false});

  final String message;
  final bool error;
  final bool disabled;
}

/// 验证已有密码时最多能输几位：新设的只能 4–12 位，但老版本没限长度，存过 17 位以上的纯数字密码（审计 M8），要输得进去。
const verifyMaxLength = 64;

/// 返回 null 表示过了（调用方自己关页面或解锁）。
typedef PasscodeSubmit = Future<EntryFeedback?> Function(String input);

/// 系统锁屏样式的密码输入：标题、提示行、圆点密码位、圆形数字键。启动密码页、改设置的密码弹窗、设置密码共用。
/// [digitsOnly] 为 false 时（老版本设过带字母的密码）换成安全输入框加系统键盘。
class PasscodeField extends StatefulWidget {
  const PasscodeField({
    super.key,
    this.title,
    required this.message,
    this.messageIsError = false,
    required this.onSubmit,
    this.digitsOnly = true,
    this.minLength = 1,
    this.maxLength = verifyMaxLength,
    this.keySize = 76,
    this.expand = false,
  });

  final String? title;
  final String message;
  final bool messageIsError;
  final PasscodeSubmit onSubmit;
  final bool digitsOnly;
  final int minLength;
  final int maxLength;

  /// 数字键直径。
  final double keySize;

  /// 整页时上半部分和键盘之间撑开，键盘靠下，像系统锁屏。
  final bool expand;

  @override
  State<PasscodeField> createState() => _PasscodeFieldState();
}

class _PasscodeFieldState extends State<PasscodeField> with SingleTickerProviderStateMixin {
  late final _shake = AnimationController(vsync: this, duration: const Duration(milliseconds: 380));
  final _focus = FocusNode();
  final _text = TextEditingController();
  String _input = '';
  EntryFeedback? _feedback;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    // 带字母的老密码用输入框：锁屏盖在别的页面上，autofocus 不一定抢得到焦点，直接要
    if (!widget.digitsOnly) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _focus.requestFocus();
      });
    }
  }

  @override
  void dispose() {
    _shake.dispose();
    _focus.dispose();
    _text.dispose();
    super.dispose();
  }

  bool get _disabled => _busy || (_feedback?.disabled ?? false);

  String get _value => widget.digitsOnly ? _input : _text.text;

  void _type(String digit) {
    if (_disabled || _input.length >= widget.maxLength) return;
    HapticFeedback.lightImpact();
    setState(() => _input += digit);
  }

  void _delete({bool all = false}) {
    if (_disabled || _input.isEmpty) return;
    HapticFeedback.lightImpact();
    setState(() => _input = all ? '' : _input.substring(0, _input.length - 1));
  }

  Future<void> _submit() async {
    if (_disabled || _value.length < widget.minLength) return;
    setState(() => _busy = true);
    final feedback = await widget.onSubmit(_value);
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (feedback == null) return;
      _feedback = feedback;
      _input = '';
      _text.clear();
    });
    if (feedback?.error ?? false) {
      HapticFeedback.vibrate();
      _shake.forward(from: 0);
    }
    if (!widget.digitsOnly && feedback != null && !feedback.disabled) _focus.requestFocus();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final feedback = _feedback;
    final message = feedback?.message ?? widget.message;
    final isError = feedback?.error ?? widget.messageIsError;
    final top = Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (widget.title != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Text(
              widget.title!,
              textAlign: TextAlign.center,
              style: theme.textTheme.headlineMedium?.copyWith(color: colors.onSurface),
            ),
          ),
        ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 40),
          child: Text(
            message,
            textAlign: TextAlign.center,
            style: theme.textTheme.bodyMedium?.copyWith(color: isError ? colors.error : colors.onSurfaceVariant),
          ),
        ),
        const SizedBox(height: 20),
        AnimatedBuilder(
          animation: _shake,
          builder: (context, child) {
            final t = _shake.value;
            return Transform.translate(offset: Offset(math.sin(t * math.pi * 6) * 14 * (1 - t), 0), child: child);
          },
          child: widget.digitsOnly ? _PinDots(count: _input.length) : _textInput(context),
        ),
      ],
    );
    if (!widget.digitsOnly) return top;
    final pad = _PinPad(
      keySize: widget.keySize,
      enabled: !_disabled,
      canSubmit: _input.length >= widget.minLength,
      onDigit: _type,
      onDelete: _delete,
      onSubmit: _submit,
    );
    return Column(
      mainAxisSize: widget.expand ? MainAxisSize.max : MainAxisSize.min,
      children: [
        if (widget.expand) const Spacer(flex: 3),
        top,
        if (widget.expand) const Spacer(flex: 2) else const SizedBox(height: 28),
        pad,
      ],
    );
  }

  Widget _textInput(BuildContext context) {
    final l = AppLocalizations.of(context)!;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        TextField(
          controller: _text,
          focusNode: _focus,
          enabled: !_disabled,
          obscureText: true,
          textAlign: TextAlign.center,
          textInputAction: TextInputAction.done,
          style: Theme.of(context).textTheme.titleLarge,
          decoration: const InputDecoration(border: OutlineInputBorder()),
          onSubmitted: (_) => _submit(),
        ),
        const SizedBox(height: 16),
        SizedBox(
          width: double.infinity,
          child: FilledButton(onPressed: _disabled ? null : _submit, child: Text(l.okButton)),
        ),
      ],
    );
  }
}

/// 已输入几位就实心几个；不足 4 位用空心圈补到 4 个，超过就跟着变长，不透露密码有几位。
class _PinDots extends StatelessWidget {
  const _PinDots({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final slots = math.max(4, count);
    return ExcludeSemantics(
      child: SizedBox(
        height: 16,
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (var i = 0; i < slots; i++)
              Padding(
                padding: EdgeInsets.only(left: i == 0 ? 0 : 18),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 90),
                  width: 14,
                  height: 14,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: i < count ? colors.onSurface : Colors.transparent,
                    border: Border.all(color: i < count ? colors.onSurface : colors.outline, width: 1.5),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// 3×4 圆形数字键：最后一行是删除（长按清空）、0、确认。
class _PinPad extends StatelessWidget {
  const _PinPad({
    required this.keySize,
    required this.enabled,
    required this.canSubmit,
    required this.onDigit,
    required this.onDelete,
    required this.onSubmit,
  });

  final double keySize;
  final bool enabled;
  final bool canSubmit;
  final ValueChanged<String> onDigit;
  final void Function({bool all}) onDelete;
  final VoidCallback onSubmit;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context)!;
    final colors = Theme.of(context).colorScheme;
    final gap = keySize * 0.36;
    Widget digit(String d) => _Key(
      size: keySize,
      fill: colors.surfaceContainerHighest,
      onTap: enabled ? () => onDigit(d) : null,
      child: Text(
        d,
        style: TextStyle(
          fontSize: keySize * 0.4,
          fontWeight: FontWeight.w400,
          color: colors.onSurface,
          fontFeatures: const [FontFeature.tabularFigures()],
        ),
      ),
    );
    Widget row(List<Widget> keys) => Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (var i = 0; i < keys.length; i++) ...[if (i > 0) SizedBox(width: gap), keys[i]],
      ],
    );
    final submit = enabled && canSubmit;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (final r in const [
          ['1', '2', '3'],
          ['4', '5', '6'],
          ['7', '8', '9'],
        ]) ...[
          row([for (final d in r) digit(d)]),
          SizedBox(height: keySize * 0.2),
        ],
        row([
          _Key(
            size: keySize,
            semanticLabel: l.deleteKey,
            onTap: enabled ? onDelete : null,
            onLongPress: enabled ? () => onDelete(all: true) : null,
            child: Icon(Icons.backspace_outlined, color: colors.onSurfaceVariant),
          ),
          digit('0'),
          _Key(
            size: keySize,
            semanticLabel: l.okButton,
            fill: submit ? colors.primary : null,
            onTap: submit ? onSubmit : null,
            child: Icon(Icons.check, size: keySize * 0.38, color: submit ? colors.onPrimary : colors.outline),
          ),
        ]),
      ],
    );
  }
}

class _Key extends StatelessWidget {
  const _Key({required this.size, required this.child, this.fill, this.onTap, this.onLongPress, this.semanticLabel});

  final double size;
  final Widget child;
  final Color? fill;
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: semanticLabel,
      child: Material(
        color: fill ?? Colors.transparent,
        shape: const CircleBorder(),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          onLongPress: onLongPress,
          child: SizedBox.square(dimension: size, child: Center(child: child)),
        ),
      ),
    );
  }
}
