package app.hoot.ui.main

import kotlin.random.Random

object HootQuips {
    private val quipList = listOf(
        "Made for your phone. Ready for whatever you just shared.",
        "These snowflakes are the only things I let drift.",
        "Tap a format and I’ll do the little pixel shuffle.",
        "One file, one tiny owl, plenty of useful options.",
        "Your photo can have a fresh format before your screen dims.",
        "Android handed me this file. I’ll keep the steps simple.",
        "A smaller image can make a very happy Downloads folder.",
        "Need a PNG? I can help that picture feel at home.",
        "Your original stays put while I make a new copy.",
        "Thumb ready? The format wheel is waiting.",
        "I packed a whole toolkit into this little screen.",
        "A quick turn of the wheel can tidy up that file.",
        "Pixels in, polished file out. Nice and easy.",
        "Your phone has places to be. I’ll keep this conversion short.",
        "The file came along for the ride. Let’s make it useful.",
        "I like my snow light and my image files lighter.",
        "This screen is small. The file possibilities aren’t.",
        "A fresh extension looks good on that photo.",
        "Share a file with me and I’ll find its next shape.",
        "The owl is ready when your next file is.",
        "Nothing to plug in. Just choose a file and spin the wheel.",
        "A little winter sparkle while your files get sorted."
    )

    fun getRandomQuip(): String {
        return quipList[Random.nextInt(quipList.size)]
    }
}
