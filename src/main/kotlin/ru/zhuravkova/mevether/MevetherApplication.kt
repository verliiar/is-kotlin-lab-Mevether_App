package ru.zhuravkova.mevether

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MevetherApplication

fun main(args: Array<String>) {
	runApplication<MevetherApplication>(*args)
}
