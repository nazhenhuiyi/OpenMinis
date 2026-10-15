package com.openminis.app.data.murmur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases shaped like what the model tags in real murmurs (made-up sentences, same patterns). */
class DishRulesTest {
    @Test fun aRecipeKeepsTheDishNotTheIngredients() = assertEquals(
        listOf("咖喱"),
        DishRules.dishes(listOf("咖喱", "花椒", "牛肉块", "茄子", "牛奶"), "晚上做了个咖喱，比想象中简单……"),
    )

    @Test fun aMealPlanKeepsTheDishes() = assertEquals(
        listOf("口蘑片丝瓜鸡蛋汤"),
        DishRules.dishes(listOf("丝瓜", "口蘑片丝瓜鸡蛋汤", "姜片", "大蒜", "胡椒粉"), "口蘑片丝瓜鸡蛋汤：先把口蘑煸香，再放姜片、大蒜"),
    )

    @Test fun plantsAreNotFood() =
        assertEquals(listOf("牛肉面"), DishRules.dishes(listOf("牛肉面", "绣球花"), "中午一碗牛肉面，路边绣球花开了。"))

    @Test fun aNameCutShortIsMadeWhole() =
        assertEquals(listOf("厚蛋烧"), DishRules.dishes(listOf("厚蛋"), "早上的厚蛋烧出锅了"))

    @Test fun singleDishesStay() {
        assertEquals(listOf("宫保鸡丁"), DishRules.dishes(listOf("宫保鸡丁"), "今天做的宫保鸡丁很成功"))
        assertEquals(listOf("绿茶", "红茶"), DishRules.dishes(listOf("绿茶", "红茶"), "下午喝了绿茶，还是红茶更合口。"))
        assertEquals(emptyList<String>(), DishRules.dishes(listOf("鸭"), "卤鸭做好了"))
    }

    @Test fun homemadeIsAboutMakingIt() {
        assertTrue(DishRules.homemade("今天做的宫保鸡丁很成功"))
        assertTrue(DishRules.homemade("制作了一罐泡菜，等两周看看"))
        assertTrue(DishRules.homemade("早上的厚蛋烧出锅了"))
        assertFalse(DishRules.homemade("隔壁飘来红烧肉的香味，好馋"))
        assertFalse(DishRules.homemade("这家烤鸭味道很足，下次还来"))
        assertFalse(DishRules.homemade("楼下有人在熬猪油，满楼道都香"))
        assertFalse(DishRules.homemade("忙了一整天，好像什么都没做成，又好像做了很多事情，很累。晚上和同事去吃了烧烤", "烧烤"))
        assertTrue(DishRules.homemade("学会一道新菜，葱姜炒蟹，先把蟹处理干净", "葱姜炒蟹"))
    }

    @Test fun termsAreNotFood() =
        assertEquals(listOf("土豆米饭"), DishRules.dishes(listOf("土豆米饭", "RS3"), "放凉的土豆米饭里有抗性淀粉 RS3"))
}
