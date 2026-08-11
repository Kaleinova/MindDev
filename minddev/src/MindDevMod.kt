package minddev

import arc.Core
import arc.Events
import arc.util.Log
import arc.util.Time
import mindustry.game.EventType.ClientLoadEvent
import mindustry.mod.Mod
import mindustry.ui.dialogs.BaseDialog

class MindDevMod : Mod() {

    init {
        Log.info("Loaded ExampleKotlinMod constructor.")

        //listen for game load event
        Events.on(ClientLoadEvent::class.java) {
            // 注入 bundle
            mlogix.util.I18N.bundle = Core.bundle

            //show dialog upon startup
            Time.runTask(10f) {
                BaseDialog("frog").apply {
                    cont.apply {
                        add("behold").row()
                        //mod sprites are prefixed with the mod name (this mod is called 'example-kotlin-mod' in its config)
                        image(Core.atlas.find("example-kotlin-mod-frog")).pad(20f).row()
                        button("I see") { hide() }.size(100f, 50f)
                    }
                    show()
                }
            }
        }
    }

    override fun loadContent() {
        Log.info("Loading some example content.")
    }
}
