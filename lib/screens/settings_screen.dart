import 'package:flutter/material.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Settings'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      ),
      body: ListView(
        children: [
          const SizedBox(height: 16),
          Center(
            child: Column(
              children: [
                const Icon(Icons.download_rounded, size: 64, color: Colors.blue),
                const SizedBox(height: 8),
                const Text(
                  'InstaEmbed',
                  style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
                ),
                const Text('v1.0.0', style: TextStyle(color: Colors.grey)),
              ],
            ),
          ),
          const SizedBox(height: 32),
          const Divider(),
          ListTile(
            leading: const Icon(Icons.info_outline),
            title: const Text('How to use'),
            subtitle: const Text('Share Instagram links to this app'),
            onTap: () {
              showDialog(
                context: context,
                builder: (_) => AlertDialog(
                  title: const Text('How to use'),
                  content: const Text(
                    '1. Open Instagram and find a video or reel\n'
                    '2. Tap the Share button\n'
                    '3. Select InstaEmbed from the share sheet\n'
                    '4. Tap "Share to Discord" and pick your channel\n'
                    '5. The video is sent from your account!',
                  ),
                  actions: [
                    TextButton(
                      onPressed: () => Navigator.pop(context),
                      child: const Text('Got it'),
                    ),
                  ],
                ),
              );
            },
          ),
          const Divider(),
          const ListTile(
            leading: const Icon(Icons.code),
            title: const Text('No accounts required'),
            subtitle: const Text('Works entirely on your device'),
          ),
        ],
      ),
    );
  }
}
