import 'dart:convert';
import 'package:http/http.dart' as http;
import '../models/post_data.dart';

class InstagramFetcher {
  // Instagram 401s logged-out requests to /graphql/query/ since Oct 2026; /api/graphql still
  // works when it carries the page's LSD token
  static const String _graphqlEndpoint = 'https://www.instagram.com/api/graphql';
  static const String _docId = '27128499623469141';
  static const String _igAppId = '936619743392459';
  static const String _userAgent =
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36';

  static String? extractShortcode(String url) {
    final patterns = [
      RegExp(r'instagram\.com/(?:reel|p)/([A-Za-z0-9_-]+)'),
      RegExp(r'instagr\.am/(?:reel|p)/([A-Za-z0-9_-]+)'),
    ];
    for (final pattern in patterns) {
      final match = pattern.firstMatch(url);
      if (match != null) return match.group(1);
    }
    return null;
  }

  /// Loads instagram.com like a browser navigation to collect its cookies and the LSD token.
  static Future<({String? csrfToken, String? lsd, String cookies})> _fetchSession() async {
    try {
      final response = await http.get(
        Uri.parse('https://www.instagram.com/'),
        headers: {
          'User-Agent': _userAgent,
          'Accept': 'text/html',
          'Sec-Fetch-Dest': 'document',
          'Sec-Fetch-Mode': 'navigate',
          'Sec-Fetch-Site': 'none',
        },
      );
      // package:http folds multiple Set-Cookie headers into one comma-separated value
      final setCookie = response.headers['set-cookie'] ?? '';
      final cookies = RegExp(r'(?:^|,\s*)([A-Za-z0-9_]+=[^;,]*)')
          .allMatches(setCookie)
          .map((m) => m.group(1)!)
          .toList();
      final csrf = cookies
          .where((c) => c.startsWith('csrftoken='))
          .map((c) => c.substring('csrftoken='.length))
          .firstOrNull;
      final lsd = RegExp(r'"LSD",\[\],\{[^{}]*"token":"([^"]+)"').firstMatch(response.body)?.group(1);
      return (csrfToken: csrf, lsd: lsd, cookies: cookies.join('; '));
    } catch (_) {
      return (csrfToken: null, lsd: null, cookies: '');
    }
  }

  static Future<PostData> fetchPost(String url) async {
    final shortcode = extractShortcode(url);
    if (shortcode == null) {
      throw Exception('Could not extract shortcode from URL');
    }

    final session = await _fetchSession();

    final variables = jsonEncode({
      'shortcode': shortcode,
      '__relay_internal__pv__PolarisAIGMMediaWebLabelEnabledrelayprovider': false,
    });

    final headers = {
      'User-Agent': _userAgent,
      'X-IG-App-ID': _igAppId,
      'Content-Type': 'application/x-www-form-urlencoded',
      'X-CSRFToken': ?session.csrfToken,
      'X-FB-LSD': ?session.lsd,
      if (session.cookies.isNotEmpty) 'Cookie': session.cookies,
      'Origin': 'https://www.instagram.com',
      'Referer': 'https://www.instagram.com/p/$shortcode/',
      'Sec-Fetch-Site': 'same-origin',
      'Sec-Fetch-Mode': 'cors',
      'Sec-Fetch-Dest': 'empty',
    };
    final lsdParam = session.lsd != null ? 'lsd=${Uri.encodeComponent(session.lsd!)}&' : '';

    final response = await http.post(
      Uri.parse(_graphqlEndpoint),
      headers: headers,
      body: '${lsdParam}doc_id=$_docId&variables=${Uri.encodeComponent(variables)}',
    );

    if (response.statusCode != 200) {
      throw Exception('Instagram API returned ${response.statusCode}');
    }

    final data = jsonDecode(response.body);
    final items = data['data']?['xdt_api__v1__media__shortcode__web_info']?['items'];
    if (items == null || items.isEmpty) {
      throw Exception('No media found in response');
    }

    final item = items[0];

    String? videoUrl;
    String? imageUrl;

    if (item['video_versions'] != null && (item['video_versions'] as List).isNotEmpty) {
      videoUrl = item['video_versions'][0]['url'];
    }

    if (item['image_versions2'] != null && item['image_versions2']['candidates'] != null) {
      final candidates = item['image_versions2']['candidates'] as List;
      if (candidates.isNotEmpty) {
        imageUrl = candidates[0]['url'];
      }
    }

    if (videoUrl == null && imageUrl == null) {
      throw Exception('No downloadable media found');
    }

    return PostData(
      shortcode: shortcode,
      videoUrl: videoUrl,
      imageUrl: imageUrl,
      caption: item['caption']?['text'],
      authorUsername: item['user']?['username'],
      authorProfilePic: item['user']?['profile_pic_url'],
      likeCount: item['like_count'] ?? 0,
    );
  }
}
