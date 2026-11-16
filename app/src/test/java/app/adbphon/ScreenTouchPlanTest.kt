package app.adbphon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTouchPlanTest {
    private val t = mapOf(
        StIds.A to StTarget(900f, 500f),
        StIds.B to StTarget(1000f, 400f),
        StIds.RT to StTarget(1100f, 100f),
        StIds.START to StTarget(640f, 40f),
        StIds.LS to StTarget(200f, 500f, 100f),
    )

    @Test fun press_hold_release_button() {
        val p = ScreenTouchPlan()
        val s1 = p.step(StInput(setOf(StIds.A)), t)
        assertEquals(1, s1.size)
        val a = s1[0]
        assertEquals(StIds.A, a.ctl); assertEquals(-1, a.cont); assertTrue(a.willContinue)
        assertEquals(900f, a.x0); assertEquals(500f, a.y0); assertEquals(900f, a.x1); assertEquals(1L, a.durMs)
        // удержание без изменений: жест не нужен, палец лежит
        assertTrue(p.step(StInput(setOf(StIds.A)), t).isEmpty())
        assertEquals(setOf(StIds.A), p.down)
        // отпускание: продолжение того же штриха без продолжения дальше
        val s3 = p.step(StInput(emptySet()), t)
        assertEquals(1, s3.size); assertEquals(a.id, s3[0].cont); assertFalse(s3[0].willContinue)
        assertEquals(900f, s3[0].x0); assertEquals(900f, s3[0].x1)
        assertTrue(p.down.isEmpty())
    }

    @Test fun multitouch_keeps_all_held_strokes_in_every_gesture() {
        val p = ScreenTouchPlan()
        val s1 = p.step(StInput(setOf(StIds.A)), t)
        val s2 = p.step(StInput(setOf(StIds.A, StIds.B)), t)
        assertEquals(2, s2.size)
        val contA = s2.first { it.ctl == StIds.A }
        assertEquals(s1[0].id, contA.cont); assertTrue(contA.willContinue); assertEquals(1L, contA.durMs)
        val newB = s2.first { it.ctl == StIds.B }
        assertEquals(-1, newB.cont); assertTrue(newB.willContinue)
        // отпустить A при зажатой B: подъём A и удержание B в одном жесте
        val s3 = p.step(StInput(setOf(StIds.B)), t)
        assertEquals(2, s3.size)
        assertFalse(s3.first { it.ctl == StIds.A }.willContinue)
        val holdB = s3.first { it.ctl == StIds.B }
        assertEquals(newB.id, holdB.cont); assertTrue(holdB.willContinue)
    }

    @Test fun stick_starts_at_center_moves_and_continues_from_last_point() {
        val p = ScreenTouchPlan()
        val s1 = p.step(StInput(emptySet(), sticks = mapOf(StIds.LS to (1f to 0f))), t)
        assertEquals(1, s1.size)
        val d = s1[0]
        assertEquals(200f, d.x0); assertEquals(500f, d.y0); assertEquals(300f, d.x1); assertEquals(500f, d.y1)
        assertEquals(16L, d.durMs); assertTrue(d.willContinue)
        // сдвиг вверх: продолжение начинается точно в конце прошлого штриха
        val s2 = p.step(StInput(emptySet(), sticks = mapOf(StIds.LS to (0f to -0.5f))), t)
        assertEquals(1, s2.size)
        assertEquals(d.id, s2[0].cont); assertEquals(300f, s2[0].x0); assertEquals(500f, s2[0].y0)
        assertEquals(200f, s2[0].x1); assertEquals(450f, s2[0].y1); assertTrue(s2[0].willContinue)
        // больше единицы ограничивается радиусом, координаты целые
        val s3 = p.step(StInput(emptySet(), sticks = mapOf(StIds.LS to (3f to 4f))), t)
        assertEquals(260f, s3[0].x1); assertEquals(580f, s3[0].y1)
        // в мёртвой зоне: подъём
        val s4 = p.step(StInput(emptySet(), sticks = mapOf(StIds.LS to (0.01f to 0f))), t)
        assertEquals(1, s4.size); assertFalse(s4[0].willContinue); assertEquals(s3[0].id, s4[0].cont)
    }

    @Test fun quick_tap_between_steps_becomes_short_tap() {
        val p = ScreenTouchPlan()
        val s = p.step(StInput(emptySet(), taps = setOf(StIds.START, StIds.RT)), t)
        assertEquals(2, s.size)
        s.forEach { assertEquals(-1, it.cont); assertFalse(it.willContinue); assertEquals(50L, it.durMs); assertEquals(it.x0, it.x1) }
        assertTrue(p.down.isEmpty())
        // нажатие, которое ещё держится, не даёт лишнего короткого касания
        val s2 = p.step(StInput(setOf(StIds.A), taps = setOf(StIds.A)), t)
        assertEquals(1, s2.size); assertTrue(s2[0].willContinue)
    }

    @Test fun no_target_means_no_stroke_and_reset_forgets_pointers() {
        val p = ScreenTouchPlan()
        assertTrue(p.step(StInput(setOf(StIds.Y, StIds.DU)), t).isEmpty())
        p.step(StInput(setOf(StIds.A)), t)
        p.reset()
        val s = p.step(StInput(setOf(StIds.A)), t)
        assertEquals(1, s.size); assertEquals(-1, s[0].cont)      // после отмены жеста прижимаем заново
    }

    @Test fun stroke_limit_is_respected() {
        val many = StIds.all.filter { it !in StIds.sticks }.mapIndexed { i, id -> id to StTarget(10f * i, 10f) }.toMap()
        val p = ScreenTouchPlan(maxStrokes = 10)
        val s = p.step(StInput(many.keys), many)
        assertEquals(10, s.size)
    }

    @Test fun from_pad_maps_bits_triggers_hat_and_axes() {
        val axes = floatArrayOf(0.5f, -0.5f, 0f, 1f)
        val inp = StInput.fromPad((1 shl Pad.A) or (1 shl Pad.START) or (1 shl Pad.HOME), lt = true, rt = false, hat = 1, axes = axes)
        assertEquals(setOf(StIds.A, StIds.START, StIds.LT, StIds.DU, StIds.DR), inp.held)
        assertEquals(0.5f to -0.5f, inp.sticks[StIds.LS]); assertEquals(0f to 1f, inp.sticks[StIds.RS])
        assertEquals(setOf(StIds.DD, StIds.DL), StIds.ofHat(5))
        assertTrue(StIds.ofHat(8).isEmpty())
    }

    @Test fun profile_roundtrip_key_and_cover_check() {
        val enc = StProfile.encode(t, 1280, 720)
        val dec = StProfile.decode(enc, 1280, 720)
        assertEquals(t.keys, dec.keys)
        assertEquals(900f, dec.getValue(StIds.A).x, 0.5f); assertEquals(500f, dec.getValue(StIds.A).y, 0.5f)
        assertEquals(100f, dec.getValue(StIds.LS).r, 0.5f)
        // другой размер экрана: точки масштабируются
        assertEquals(1800f, StProfile.decode(enc, 2560, 1440).getValue(StIds.A).x, 1f)
        // мусор и чужие метки пропускаются
        assertEquals(setOf(StIds.B), StProfile.decode("ZZ:0.1,0.1,0;B:0.5,0.5,0;A:abc;X:9,9,0", 100, 100).keys)
        assertTrue(StProfile.decode(null, 100, 100).isEmpty())
        assertEquals("p:com.game:land", StProfile.key("com.game", 1280, 720))
        assertEquals("p:com.game:port", StProfile.key("com.game", 720, 1280))
        assertEquals(setOf(StIds.A), StProfile.covered(t, listOf(floatArrayOf(850f, 450f, 950f, 550f))))
    }
}
