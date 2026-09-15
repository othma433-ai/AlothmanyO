package com.alothmany.wa

import android.app.Application
import com.alothmany.wa.data.AppDatabase

class WaApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.create(this) }
}
