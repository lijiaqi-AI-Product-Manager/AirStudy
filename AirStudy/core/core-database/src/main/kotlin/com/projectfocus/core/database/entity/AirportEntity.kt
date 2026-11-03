package com.projectfocus.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey


@Entity(
    tableName = "airports",
    indices = [
        Index(value = ["iataCode"], name = "index_airports_iataCode"),
        Index(value = ["icaoCode"], name = "index_airports_icaoCode"),
        Index(value = ["country", "popularity"], name = "index_airports_country_popularity"),
    ],
)
data class AirportEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Long,

    @ColumnInfo(name = "iataCode")
    val iataCode: String,

    @ColumnInfo(name = "icaoCode")
    val icaoCode: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "city")
    val city: String,

    @ColumnInfo(name = "country")
    val country: String,

    @ColumnInfo(name = "latitude")
    val latitude: Double,

    @ColumnInfo(name = "longitude")
    val longitude: Double,


    @ColumnInfo(name = "elevation")
    val elevation: Int,

    /** 大洲代码（AF / AN / AS / EU / NA / OC / SA） */
    @ColumnInfo(name = "continent")
    val continent: String,

    /** large_airport / medium_airport / small_airport / heliport / seaplane_base / closed */
    @ColumnInfo(name = "type")
    val type: String,

    /** 热度排序权重。列表不是字母序，是热度序 */
    @ColumnInfo(name = "popularity")
    val popularity: Int,

    /** 付费专享标记（0/1）。第一道内容级付费墙 */
    @ColumnInfo(name = "isPremiumOnly")
    val isPremiumOnly: Boolean,


    @ColumnInfo(name = "nameZh")
    val nameZh: String? = null,

    /** 中文城市名（如「北京」）。见 [nameZh] */
    @ColumnInfo(name = "cityZh")
    val cityZh: String? = null,
)
