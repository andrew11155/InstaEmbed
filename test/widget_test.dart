import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:instaembed/main.dart';

void main() {
  testWidgets('Home screen shows title and URL field', (WidgetTester tester) async {
    await tester.pumpWidget(const InstaEmbedApp());

    expect(find.text('InstaEmbed'), findsWidgets);
    expect(find.byType(TextField), findsOneWidget);
  });
}
