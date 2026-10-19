package com.arktosbrowser.mobile.search

enum class Suggestions(val index: Int) {
    GOOGLE(0),
    DUCK(1),
    BAIDU(2),
    NAVER(3),
    NONE(5);

    companion object {
        fun from(value: Int): Suggestions {
            return when (value) {
                0 -> GOOGLE
                1 -> DUCK
                2 -> BAIDU
                3 -> NAVER
                5 -> NONE
                else -> GOOGLE
            }
        }
    }
}