package com.github.alondero.nestlin

import java.nio.file.Paths


fun main(args : Array<String>) {
    if (args.size == 0) {
        println("Please provide a rom file as an argument")
        return
    }

    Nestlin().apply {
        this.load(Paths.get(args[0]))
        this.powerReset()
        this.start()
    }
}
