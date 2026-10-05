package com.vibenav.data.osm

import com.vibenav.data.model.*
import kotlin.math.*

object CurvyRouteEngine {

    data class GeneratedRoute(
        val title: String,
        val totalDistanceKm: Float,
        val estimatedMinutes: Int,
        val curvinessScore: Int,
        val elevationGainMeters: Int,
        val points: List<GeoPoint>,
        val instructions: List<TurnInstruction>
    )

    /**
     * Generates a curvy motorcycle roundtrip inspired by TomTom GoRide.
     * Starts and finishes at the current location, looping through scenic waypoints
     * biased towards chosen directions (N, S, E, W, etc.) and distance.
     */
    fun generateRoundtrip(
        start: GeoPoint,
        config: RoundtripConfig
    ): GeneratedRoute {
        val targetDistanceKm = config.radiusKm.toFloat()
        val directions = if (config.selectedDirections.isNotEmpty()) {
            config.selectedDirections.toList()
        } else {
            listOf(CompassDirection.NORTH, CompassDirection.EAST)
        }

        // Calculate a polygonal loop based on directions
        // Radius of loop is roughly targetDistance / 2*PI, adjusted for curviness
        val curvinessFactor = when (config.roadType) {
            RoadTypePreference.FASTEST -> 1.0f
            RoadTypePreference.CURVY -> 1.35f
            RoadTypePreference.VERY_CURVY -> 1.75f
        }

        val effectiveRadiusKm = (targetDistanceKm / (2 * Math.PI.toFloat())) * 0.9f
        val points = mutableListOf<GeoPoint>()
        points.add(start)

        // Generate 6 to 10 loop waypoints around the bias direction
        val numWaypoints = 8
        val baseAngle = directions.map { it.angleDegrees }.average().toFloat()

        // Loop offsets
        val intermediatePoints = mutableListOf<GeoPoint>()
        for (i in 1..numWaypoints) {
            val progress = i.toFloat() / (numWaypoints + 1)
            // Loop angle starts at start, goes in loop around baseAngle
            val loopAngleDeg = (baseAngle - 90f + (progress * 360f)) % 360f
            val rad = Math.toRadians(loopAngleDeg.toDouble())

            // Vary distance with sine wave for twisty natural feel
            val twistFactor = if (config.roadType == RoadTypePreference.VERY_CURVY) {
                1.0 + 0.35 * sin(progress * Math.PI * 4)
            } else if (config.roadType == RoadTypePreference.CURVY) {
                1.0 + 0.20 * sin(progress * Math.PI * 3)
            } else {
                1.0
            }

            val distKm = effectiveRadiusKm * twistFactor
            // 1 deg latitude ≈ 111.32 km
            val deltaLat = (distKm * cos(rad)) / 110.574
            val deltaLon = (distKm * sin(rad)) / (111.320 * cos(Math.toRadians(start.latitude)))

            val alt = start.altitudeMeters + (sin(progress * Math.PI * 2) * 280.0) + (i * 12.0)
            intermediatePoints.add(GeoPoint(start.latitude + deltaLat, start.longitude + deltaLon, alt))
        }

        // Densify points with micro-curves to represent real curvy roads
        for (wp in intermediatePoints) {
            val last = points.last()
            val steps = if (config.roadType == RoadTypePreference.VERY_CURVY) 12 else 8
            for (s in 1..steps) {
                val t = s.toFloat() / steps
                val lat = last.latitude + (wp.latitude - last.latitude) * t
                val lon = last.longitude + (wp.longitude - last.longitude) * t

                // Add road twist offset
                val perpendicularOffset = if (config.roadType != RoadTypePreference.FASTEST) {
                    sin(t * Math.PI) * 0.0035 * (if (s % 2 == 0) 1 else -1)
                } else 0.0

                val alt = last.altitudeMeters + (wp.altitudeMeters - last.altitudeMeters) * t
                points.add(GeoPoint(lat + perpendicularOffset, lon + perpendicularOffset * 0.7, alt))
            }
        }

        // Close the loop back to start
        val lastPoint = points.last()
        val closingSteps = 8
        for (s in 1..closingSteps) {
            val t = s.toFloat() / closingSteps
            val lat = lastPoint.latitude + (start.latitude - lastPoint.latitude) * t
            val lon = lastPoint.longitude + (start.longitude - lastPoint.longitude) * t
            val alt = lastPoint.altitudeMeters + (start.altitudeMeters - lastPoint.altitudeMeters) * t
            points.add(GeoPoint(lat, lon, alt))
        }

        // Calculate actual total distance
        var actualDistanceKm = 0f
        var elevationGain = 0
        for (i in 0 until points.size - 1) {
            val d = GpxParser.haversineDistanceKm(
                points[i].latitude, points[i].longitude,
                points[i + 1].latitude, points[i + 1].longitude
            ).toFloat()
            actualDistanceKm += d
            val altDiff = points[i + 1].altitudeMeters - points[i].altitudeMeters
            if (altDiff > 0) elevationGain += altDiff.toInt()
        }

        val avgSpeed = when (config.roadType) {
            RoadTypePreference.FASTEST -> 75f
            RoadTypePreference.CURVY -> 58f
            RoadTypePreference.VERY_CURVY -> 44f
        }
        val estimatedMinutes = ((actualDistanceKm / avgSpeed) * 60).toInt().coerceAtLeast(15)

        val curvinessScore = when (config.roadType) {
            RoadTypePreference.FASTEST -> 35
            RoadTypePreference.CURVY -> 82
            RoadTypePreference.VERY_CURVY -> 98
        }

        val directionsStr = directions.joinToString(", ") { it.code }
        val title = "Curvy Loop ($directionsStr) - ${actualDistanceKm.toInt()} km"

        val instructions = generateTurnInstructions(points, config)

        return GeneratedRoute(
            title = title,
            totalDistanceKm = actualDistanceKm,
            estimatedMinutes = estimatedMinutes,
            curvinessScore = curvinessScore,
            elevationGainMeters = elevationGain,
            points = points,
            instructions = instructions
        )
    }

    /**
     * Generates a point-to-point route from start to destination with realistic road curves
     * and turn-by-turn guidance for motorcycle riders.
     */
    fun generateRouteToDestination(
        start: GeoPoint,
        destination: GeoPoint,
        destinationName: String,
        roadType: RoadTypePreference = RoadTypePreference.CURVY,
        groundPreference: GroundPreference = GroundPreference.PAVED
    ): GeneratedRoute {
        val totalDistanceKm = (GpxParser.haversineDistanceKm(
            start.latitude, start.longitude,
            destination.latitude, destination.longitude
        ) * if (roadType == RoadTypePreference.VERY_CURVY) 1.55f else if (roadType == RoadTypePreference.CURVY) 1.35f else 1.15f).toFloat()

        val points = mutableListOf<GeoPoint>()
        points.add(start)

        val numSegments = 16
        for (i in 1..numSegments) {
            val t = i.toFloat() / numSegments
            val baseLat = start.latitude + (destination.latitude - start.latitude) * t
            val baseLon = start.longitude + (destination.longitude - start.longitude) * t

            // Add serpentine twist perpendicular to straight line
            val curveMagnitude = when (roadType) {
                RoadTypePreference.FASTEST -> 0.0008
                RoadTypePreference.CURVY -> 0.0055
                RoadTypePreference.VERY_CURVY -> 0.0095
            }
            val twist = sin(t * Math.PI * 6) * curveMagnitude * (if (i % 2 == 0) 1 else -1)

            val alt = start.altitudeMeters + (destination.altitudeMeters - start.altitudeMeters) * t + sin(t * Math.PI * 2) * 120.0
            points.add(GeoPoint(baseLat + twist, baseLon + twist * 0.8, alt))
        }
        points.add(destination)

        val avgSpeed = when (roadType) {
            RoadTypePreference.FASTEST -> 80f
            RoadTypePreference.CURVY -> 58f
            RoadTypePreference.VERY_CURVY -> 42f
        }
        val estimatedMinutes = ((totalDistanceKm / avgSpeed) * 60).toInt().coerceAtLeast(8)
        val curvinessScore = when (roadType) {
            RoadTypePreference.FASTEST -> 32
            RoadTypePreference.CURVY -> 84
            RoadTypePreference.VERY_CURVY -> 97
        }

        val config = RoundtripConfig(
            radiusKm = totalDistanceKm.toInt(),
            selectedDirections = emptySet(),
            roadType = roadType,
            groundPreference = groundPreference
        )
        val instructions = generateTurnInstructions(points, config).toMutableList()
        if (instructions.isNotEmpty()) {
            instructions[instructions.size - 1] = instructions.last().copy(
                text = "Arrive at $destinationName",
                streetName = destinationName
            )
        }

        return GeneratedRoute(
            title = "Route to $destinationName",
            totalDistanceKm = totalDistanceKm,
            estimatedMinutes = estimatedMinutes,
            curvinessScore = curvinessScore,
            elevationGainMeters = abs(destination.altitudeMeters - start.altitudeMeters).toInt() + 180,
            points = points,
            instructions = instructions
        )
    }

    /**
     * Generates scenic turn-by-turn navigation instructions along a polyline.
     */
    fun generateTurnInstructions(points: List<GeoPoint>, config: RoundtripConfig): List<TurnInstruction> {
        val instructions = mutableListOf<TurnInstruction>()
        if (points.size < 3) return instructions

        val roadNames = when (config.groundPreference) {
            GroundPreference.GRAVEL -> listOf(
                "Forest Trail 4B", "Highland Ridge Track", "Old Quarry Byway",
                "Pine Pass Trail", "Timber Road 12", "Canyon Creek Way"
            )
            GroundPreference.MIXED -> listOf(
                "Ridge Valley Road", "Mountain Crest Byway", "River Gorge Pass",
                "Meadow Pass Way", "Lookout Mountain Trail", "Sun Valley Road"
            )
            GroundPreference.PAVED -> listOf(
                "State Route 9 Scenic", "Highland Pass Hwy", "Whispering Pines Way",
                "Eagle Ridge Parkway", "Silver Creek Turnpike", "Twin Peaks Corridor"
            )
        }

        instructions.add(
            TurnInstruction(
                index = 0,
                type = TurnType.START,
                text = "Head out on ${roadNames[0]}",
                distanceMeters = 450,
                streetName = roadNames[0],
                speedLimitKmh = if (config.groundPreference == GroundPreference.GRAVEL) 50 else 70,
                location = points.first()
            )
        )

        var accumulatedDistMeters = 0
        var streetIdx = 1

        val stride = max(1, points.size / 10)
        for (i in stride until points.size - stride step stride) {
            val pPrev = points[i - 1]
            val pCurr = points[i]
            val pNext = points[min(points.size - 1, i + 1)]

            val bearing1 = calculateBearing(pPrev.latitude, pPrev.longitude, pCurr.latitude, pCurr.longitude)
            val bearing2 = calculateBearing(pCurr.latitude, pCurr.longitude, pNext.latitude, pNext.longitude)

            var diff = bearing2 - bearing1
            while (diff < -180) diff += 360
            while (diff > 180) diff -= 360

            val turnType = when {
                diff in -35.0..-15.0 -> TurnType.SLIGHT_LEFT
                diff in -80.0..-35.0 -> TurnType.LEFT
                diff < -80.0 -> TurnType.SHARP_LEFT
                diff in 15.0..35.0 -> TurnType.SLIGHT_RIGHT
                diff in 35.0..80.0 -> TurnType.RIGHT
                diff > 80.0 -> TurnType.SHARP_RIGHT
                else -> TurnType.STRAIGHT
            }

            val street = roadNames[streetIdx % roadNames.size]
            streetIdx++

            val dist = (GpxParser.haversineDistanceKm(
                pCurr.latitude, pCurr.longitude,
                pNext.latitude, pNext.longitude
            ) * 1000).toInt().coerceIn(250, 4200)

            val text = when (turnType) {
                TurnType.LEFT -> "Turn left onto $street"
                TurnType.RIGHT -> "Turn right onto $street"
                TurnType.SLIGHT_LEFT -> "Bear slight left onto $street"
                TurnType.SLIGHT_RIGHT -> "Bear slight right onto $street"
                TurnType.SHARP_LEFT -> "Sharp hairpin left onto $street"
                TurnType.SHARP_RIGHT -> "Sharp hairpin right onto $street"
                else -> "Continue along $street"
            }

            val speedLimit = when {
                config.groundPreference == GroundPreference.GRAVEL -> 40
                turnType == TurnType.SHARP_LEFT || turnType == TurnType.SHARP_RIGHT -> 50
                config.roadType == RoadTypePreference.VERY_CURVY -> 60
                else -> 80
            }

            instructions.add(
                TurnInstruction(
                    index = instructions.size,
                    type = turnType,
                    text = text,
                    distanceMeters = dist,
                    streetName = street,
                    speedLimitKmh = speedLimit,
                    location = pCurr
                )
            )
        }

        instructions.add(
            TurnInstruction(
                index = instructions.size,
                type = TurnType.DESTINATION,
                text = "Arrive at destination (Roundtrip complete)",
                distanceMeters = 0,
                streetName = "Base Camp / Home",
                speedLimitKmh = 50,
                location = points.last()
            )
        )

        return instructions
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val theta = atan2(y, x)
        return (Math.toDegrees(theta) + 360.0) % 360.0
    }
}
