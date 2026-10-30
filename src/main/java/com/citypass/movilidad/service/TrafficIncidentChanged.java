package com.citypass.movilidad.service;

/** Señal de un incidente que cambió el estado de la circulación, tras persistirlo. */
public record TrafficIncidentChanged(double latitude, double longitude) { }
