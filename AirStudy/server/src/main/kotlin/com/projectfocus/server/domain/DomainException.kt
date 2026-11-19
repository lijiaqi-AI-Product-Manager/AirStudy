package com.projectfocus.server.domain

import io.ktor.http.HttpStatusCode


open class DomainException(
    /** 业务错误码，客户端按它做分支（不要解析 message） */
    val code: Int,
    /** **给用户看**的文案。必须是可以直接展示的中文，不含技术术语。 */
    val userMessage: String,
    val httpStatus: HttpStatusCode,
) : RuntimeException(userMessage)

// ═══════════════════════════════════════════════════════════
// 1xxx 认证 / 用户
// ═══════════════════════════════════════════════════════════

class UnauthorizedException(message: String = "登录已过期，请重新登录") :
    DomainException(1001, message, HttpStatusCode.Unauthorized)

class EmailAlreadyUsedException :
    DomainException(1010, "该邮箱已注册", HttpStatusCode.Conflict)

class InvalidCredentialsException :

    //   区分了就等于告诉攻击者"这个邮箱注册过"。
    DomainException(1011, "邮箱或密码不正确", HttpStatusCode.Unauthorized)

class NicknameInvalidException(reason: String) :
    DomainException(1012, reason, HttpStatusCode.BadRequest)

class UserNotFoundException :
    DomainException(1013, "用户不存在", HttpStatusCode.NotFound)

// ═══════════════════════════════════════════════════════════
// 2xxx 房间
// ═══════════════════════════════════════════════════════════

class InviteCodeInvalidException :

    //   区分了就等于给攻击者一个**枚举 6 位码**的探针（4 位 → 6 位只是多试几次）。
    DomainException(2001, "邀请码无效或已失效", HttpStatusCode.NotFound)

class RoomFullException(maxSeats: Int) :
    DomainException(2003, "房间已满（上限 $maxSeats 人）", HttpStatusCode.Conflict)

class RoomAlreadyStartedException :
    DomainException(2002, "该航班已结束", HttpStatusCode.Conflict)

class RoomNotFoundException :
    DomainException(2004, "房间不存在", HttpStatusCode.NotFound)

class CannotRescheduleException :
    DomainException(2005, "该航班已开始，无法改期", HttpStatusCode.Conflict)

class ScheduleInPastException :
    DomainException(2006, "起飞时间必须晚于当前时间", HttpStatusCode.BadRequest)

class ChatDisabledException :
    DomainException(2007, "本次航班已关闭机上聊天", HttpStatusCode.Forbidden)

class SeatTakenException(seat: String) :
    DomainException(2008, "座位 $seat 已被占用", HttpStatusCode.Conflict)

class NotInRoomException :
    DomainException(2009, "你不在任何房间中", HttpStatusCode.Conflict)

class NotRoomHostException :
    DomainException(2010, "只有房主可以执行该操作", HttpStatusCode.Forbidden)

// ═══════════════════════════════════════════════════════════
// 3xxx 飞行记录
// ═══════════════════════════════════════════════════════════

class FlightNotFoundException :
    DomainException(3001, "飞行记录不存在", HttpStatusCode.NotFound)

class FlightNotOwnedException :
    DomainException(3002, "无权操作他人的飞行记录", HttpStatusCode.Forbidden)

class FlightInvalidException(reason: String) :
    DomainException(3003, reason, HttpStatusCode.BadRequest)

// ═══════════════════════════════════════════════════════════
// 4xxx 请求格式
// ═══════════════════════════════════════════════════════════

class BadRequestException(reason: String) :
    DomainException(4001, reason, HttpStatusCode.BadRequest)
