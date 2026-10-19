package com.lagradost.cloudstream.mac

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Network test of install → search → load → links against the official repo. Run with CS_SMOKE=1. */
class CoreFlowSmokeTest {
    @Test
    fun internetArchiveCoreFlow() = runBlocking {
        if (System.getenv("CS_SMOKE") != "1") return@runBlocking
        val repo = Extensions.addRepo("https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json")
        // Install the whole official repo so missing Android stubs show up as load errors
        Extensions.listPlugins(repo.url).forEach { Extensions.install(it, repo.url) }
        assertTrue(Extensions.loadErrors.isEmpty(), "load errors: ${Extensions.loadErrors}")

        val api = APIHolder.apis.first { it.sourcePlugin?.contains("InternetArchive") == true }
        val results = api.search("night of the living dead", 1)!!.items
        println("search: ${results.take(3).map { it.name }}")
        val res = api.load(results.first().url)!!
        println("load: ${res.name} ${res::class.simpleName}")
        val data = (res as? MovieLoadResponse)?.dataUrl ?: error("expected a movie, got ${res::class.simpleName}")
        val links = mutableListOf<ExtractorLink>()
        api.loadLinks(data, false, {}, { links.add(it) })
        println("links: ${links.map { "${it.name} ${it.type} ${it.url}" }}")
        assertTrue(links.isNotEmpty())
        File("build/smoke-command.txt").writeText(Player.command("VLC", links.first(), res.name, emptyList()).joinToString("\n"))
    }
}
