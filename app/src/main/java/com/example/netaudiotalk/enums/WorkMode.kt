package com.example.netaudiotalk.enums

enum class WorkMode {
    TRANSMIT,     // 飞行员（发射端）：双向链路，不执行joinGroup，允许发送
    LISTEN_ONLY   // 观察者（收听端）：纯下行链路，必须joinGroup，强行拦截并禁止一切网络发送
}
