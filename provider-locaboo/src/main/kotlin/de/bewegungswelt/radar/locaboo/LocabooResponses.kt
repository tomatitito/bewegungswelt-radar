package de.bewegungswelt.radar.locaboo

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader

/** Only these calendar fields are decoded. In particular, events are skipped, not materialized. */
internal object LocabooResponses {
    data class Metadata(
        val resources: List<Map<String, String>>,
        val opening: List<Map<String, String>>,
        val custom: List<Map<String, String>>,
        val lead: List<Map<String, String>>,
        val earliest: Map<String, String>,
        val latest: Map<String, String>,
    )

    data class Intervals(
        val booked: List<Map<String, String>>,
        val holidays: Map<String, List<Map<String, String>>>,
    )

    fun flags(input: Reader): Map<String, String> = json(input) { reader ->
        var success: String? = null
        var flags: Map<String, String>? = null
        reader.fields { key ->
            when (key) {
                "success" -> success = reader.nextString()
                "flags" -> {
                    val values = mutableMapOf<String, String>()
                    reader.fields { id ->
                        val row = reader.row(setOf("direct_booking"))
                        values[id] = row.getValue("direct_booking")
                    }
                    flags = values
                }
                else -> reader.skipValue()
            }
        }
        require(success == "1")
        requireNotNull(flags)
    }

    fun metadata(input: Reader): Metadata = json(input) { reader ->
        var resources: List<Map<String, String>>? = null
        var opening: List<Map<String, String>>? = null
        var custom: List<Map<String, String>>? = null
        var lead: List<Map<String, String>>? = null
        var earliest: Map<String, String>? = null
        var latest: Map<String, String>? = null
        reader.fields { key ->
            when (key) {
                "cal_locations" -> resources = reader.rows(setOf("id"))
                "cal_meta" -> reader.fields { field ->
                    when (field) {
                        "location_opentime" -> opening = reader.rows(OpeningFields)
                        "custom_days" -> custom = reader.rows(OpeningFields)
                        else -> reader.skipValue()
                    }
                }
                "lead_times" -> lead = reader.rows(
                    setOf("location_id", "date", "lead_time_from_hour", "lead_time_to_hour"),
                )
                "allowed_start_dates" -> earliest = reader.stringMap()
                "max_end_dates" -> latest = reader.stringMap()
                else -> reader.skipValue()
            }
        }
        Metadata(
            requireNotNull(resources), requireNotNull(opening), requireNotNull(custom),
            requireNotNull(lead), requireNotNull(earliest), requireNotNull(latest),
        )
    }

    fun intervals(input: Reader): Intervals = json(input) { reader ->
        var booked: List<Map<String, String>>? = null
        var holidays: Map<String, List<Map<String, String>>>? = null
        reader.fields { key ->
            when (key) {
                "booked_locations" -> booked = reader.rows(setOf("location_id", "date", "from_hour", "to_hour"))
                "holidays" -> {
                    val values = mutableMapOf<String, List<Map<String, String>>>()
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.emptyArray()
                    } else {
                        reader.fields { id ->
                            values[id] = reader.rows(setOf("from", "to", "is_closing_time", "is_holiday"))
                        }
                    }
                    holidays = values
                }
                else -> reader.skipValue()
            }
        }
        Intervals(requireNotNull(booked), requireNotNull(holidays))
    }

    private val OpeningFields = setOf("location_id", "day", "from_hour", "to_hour", "customer_groups")

    private fun <T> json(input: Reader, decode: (JsonReader) -> T): T = JsonReader(input).use { reader ->
        reader.strictness = Strictness.STRICT
        val result = decode(reader)
        require(reader.peek() == JsonToken.END_DOCUMENT)
        result
    }

    private fun JsonReader.fields(read: (String) -> Unit) {
        beginObject()
        val seen = mutableSetOf<String>()
        while (hasNext()) {
            val name = nextName()
            require(seen.add(name))
            read(name)
        }
        endObject()
    }

    private fun JsonReader.row(allowed: Set<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        fields { key ->
            when {
                key !in allowed -> skipValue()
                key == "customer_groups" -> {
                    beginArray()
                    val restricted = hasNext()
                    while (hasNext()) skipValue()
                    endArray()
                    result[key] = restricted.toString()
                }
                peek() == JsonToken.BOOLEAN -> result[key] = nextBoolean().toString()
                peek() == JsonToken.STRING || peek() == JsonToken.NUMBER -> result[key] = nextString()
                else -> error("Invalid calendar field")
            }
        }
        return result
    }

    private fun JsonReader.rows(allowed: Set<String>): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()
        beginArray()
        while (hasNext()) result += row(allowed)
        endArray()
        return result
    }

    private fun JsonReader.stringMap(): Map<String, String> {
        if (peek() == JsonToken.BEGIN_ARRAY) {
            emptyArray()
            return emptyMap()
        }
        val result = mutableMapOf<String, String>()
        fields { id -> result[id] = nextString() }
        return result
    }

    private fun JsonReader.emptyArray() {
        beginArray()
        require(!hasNext())
        endArray()
    }
}
