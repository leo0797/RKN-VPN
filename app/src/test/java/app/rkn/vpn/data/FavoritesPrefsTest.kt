package app.rkn.vpn.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoritesPrefsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("rkn_vpn_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun storesAndReadsFavoriteNodeIds() {
        val prefs = Prefs(context)
        assertTrue(prefs.favoriteNodeIds.isEmpty())

        prefs.favoriteNodeIds = setOf("n1", "n2")
        assertEquals(setOf("n1", "n2"), prefs.favoriteNodeIds)

        val prefs2 = Prefs(context)
        assertEquals(setOf("n1", "n2"), prefs2.favoriteNodeIds)
    }
}
