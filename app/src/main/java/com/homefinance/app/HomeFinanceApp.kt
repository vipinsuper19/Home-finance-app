package com.homefinance.app

import android.app.Application

/**
 * Application entry point for Home Finance.
 *
 * Deliberately minimal for Task 1.1: there is no dependency-injection graph and no database
 * yet. When Room is introduced, this is where the app-wide container is created and
 * initialised. Home Finance is strictly offline-first, so no network client will ever be
 * wired up here.
 */
class HomeFinanceApp : Application()
