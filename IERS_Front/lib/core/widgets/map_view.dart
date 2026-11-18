import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';

import '../theme/app_theme.dart';

class MapPin {
  final double lat;
  final double lng;
  final IconData icon;
  final Color color;
  final String? label;
  const MapPin(this.lat, this.lng, {this.icon = Icons.location_on, this.color = AppColors.red, this.label});
}

/// OpenStreetMap (no API key). Fits all pins, draws a line between the first two.
class MapView extends StatefulWidget {
  const MapView({super.key, required this.pins, this.line = true, this.borderRadius = 0, this.interactive = true});
  final List<MapPin> pins;
  final bool line;
  final double borderRadius;
  final bool interactive;

  @override
  State<MapView> createState() => _MapViewState();
}

class _MapViewState extends State<MapView> {
  final _controller = MapController();
  bool _ready = false;

  List<LatLng> get _points => widget.pins.map((p) => LatLng(p.lat, p.lng)).toList();

  @override
  void didUpdateWidget(covariant MapView old) {
    super.didUpdateWidget(old);
    if (_ready && _changed(old.pins, widget.pins)) _fit();
  }

  bool _changed(List<MapPin> a, List<MapPin> b) {
    if (a.length != b.length) return true;
    for (var i = 0; i < a.length; i++) {
      if ((a[i].lat - b[i].lat).abs() > 0.0003 || (a[i].lng - b[i].lng).abs() > 0.0003) return true;
    }
    return false;
  }

  void _fit() {
    final pts = _points;
    if (pts.isEmpty) return;
    try {
      if (pts.length == 1) {
        _controller.move(pts.first, 15);
      } else {
        _controller.fitCamera(
          CameraFit.bounds(bounds: LatLngBounds.fromPoints(pts), padding: const EdgeInsets.all(56), maxZoom: 16),
        );
      }
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    final pts = _points;
    if (pts.isEmpty) {
      return Container(
        color: const Color(0xFFE8EBEF),
        alignment: Alignment.center,
        child: const Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.location_searching_rounded, color: AppColors.muted, size: 36),
            SizedBox(height: 8),
            Text(
              'Waiting for location…',
              style: TextStyle(color: AppColors.muted, fontWeight: FontWeight.w600),
            ),
          ],
        ),
      );
    }
    return ClipRRect(
      borderRadius: BorderRadius.circular(widget.borderRadius),
      child: FlutterMap(
        mapController: _controller,
        options: MapOptions(
          initialCenter: pts.first,
          initialZoom: 15,
          initialCameraFit: pts.length > 1
              ? CameraFit.bounds(bounds: LatLngBounds.fromPoints(pts), padding: const EdgeInsets.all(56), maxZoom: 16)
              : null,
          interactionOptions: InteractionOptions(
            flags: widget.interactive ? InteractiveFlag.all & ~InteractiveFlag.rotate : InteractiveFlag.none,
          ),
          onMapReady: () => _ready = true,
        ),
        children: [
          TileLayer(
            urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
            userAgentPackageName: 'com.iers.app',
          ),
          if (widget.line && pts.length > 1)
            PolylineLayer(
              polylines: [
                Polyline(points: [pts[0], pts[1]], strokeWidth: 4, color: AppColors.blue.withValues(alpha: 0.8)),
              ],
            ),
          MarkerLayer(
            markers: [
              for (final p in widget.pins)
                Marker(
                  point: LatLng(p.lat, p.lng),
                  width: 120,
                  height: 64,
                  alignment: Alignment.topCenter,
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (p.label != null)
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                          decoration: BoxDecoration(
                            color: Colors.white,
                            borderRadius: BorderRadius.circular(8),
                            boxShadow: const [BoxShadow(color: Colors.black26, blurRadius: 4)],
                          ),
                          child: Text(p.label!, style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w800)),
                        ),
                      Icon(p.icon, color: p.color, size: 36),
                    ],
                  ),
                ),
            ],
          ),
          const RichAttributionWidget(attributions: [TextSourceAttribution('OpenStreetMap contributors')]),
        ],
      ),
    );
  }
}
