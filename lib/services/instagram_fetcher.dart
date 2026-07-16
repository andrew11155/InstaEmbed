import 'dart:convert';
import 'package:http/http.dart' as http;
import '../models/post_data.dart';

class InstagramFetcher {
  static const String _graphqlEndpoint = 'https://www.instagram.com/graphql/query/';
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

  static Future<String?> _fetchCsrfToken() async {
    try {
      final response = await http.get(
        Uri.parse('https://www.instagram.com/'),
        headers: {'User-Agent': _userAgent},
      );
      final cookies = response.headers['set-cookie'];
      if (cookies != null) {
        final match = RegExp(r'csrftoken=([^;]+)').firstMatch(cookies);
        if (match != null) return match.group(1);
      }
    } catch (_) {}
    return null;
  }

  static Future<PostData> fetchPost(String url) async {
    final shortcode = extractShortcode(url);
    if (shortcode == null) {
      throw Exception('Could not extract shortcode from URL');
    }

    final csrfToken = await _fetchCsrfToken();

    final variables = jsonEncode({
      'shortcode': shortcode,
      '__relay_internal__pv__PolarisAIGMMediaWebLabelEnabledrelayprovider': false,
    });

    final headers = {
      'User-Agent': _userAgent,
      'X-IG-App-ID': _igAppId,
      'Content-Type': 'application/x-www-form-urlencoded',
      if (csrfToken != null) 'X-CSRFToken': csrfToken,
    };

    final response = await http.post(
      Uri.parse(_graphqlEndpoint),
      headers: headers,
      body: 'doc_id=$_docId&variables=${Uri.encodeComponent(variables)}',
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
