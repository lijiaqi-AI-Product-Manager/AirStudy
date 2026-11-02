package com.projectfocus.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey


@Entity(tableName = "stamps")
data class StampEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "countryName")
    val countryName: String,

    /** ISO 3166-1 alpha-2，如 "JP" */
    @ColumnInfo(name = "countryCode")
    val countryCode: String,

    @ColumnInfo(name = "firstVisitDate")
    val firstVisitDate: Long,

    @ColumnInfo(name = "latestVisitDate")
    val latestVisitDate: Long,

    @ColumnInfo(name = "visitCount")
    val visitCount: Int,

    @ColumnInfo(name = "focusHours")
    val focusHours: Double,


    @ColumnInfo(name = "illustrationResId")
    val illustrationResId: String,

    /** JSON 数组字符串：`["PEK","PVG"]` */
    @ColumnInfo(name = "airportCodes")
    val airportCodes: String,

    /** JSON 数组字符串：`["tokyo_tower","fuji"]` */
    @ColumnInfo(name = "unlockedLandmarkIds")
    val unlockedLandmarkIds: String,


    @ColumnInfo(name = "assetVersion", defaultValue = "1")
    val assetVersion: Int = 1,
)
