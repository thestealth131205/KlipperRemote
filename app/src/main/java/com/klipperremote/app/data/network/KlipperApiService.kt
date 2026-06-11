package com.klipperremote.app.data.network

import com.klipperremote.app.data.model.KlipperTemperaturesResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface KlipperApiService {

    // Moonraker: Abfrage aller Temperatur-Objekte
    @GET("printer/objects/query")
    suspend fun getTemperatures(
        @Query("extruder") extruder: String? = "",
        @Query("heater_bed") heaterBed: String? = "",
        @Query("heater_generic") heaterGeneric: String? = ""
    ): KlipperTemperaturesResponse

    // Moonraker JSON-RPC: Temperatur setzen
    @POST("printer/gcode/script")
    suspend fun runGcode(@Body body: Map<String, String>): Any
}
