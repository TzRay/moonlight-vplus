// Included after Sunshine's stream.cpp by the standalone benchmark build.
#include <gtest/gtest.h>
#include <fstream>

namespace stream {
  TEST(BufferPoolBench, SendClip) {
    std::ifstream input(std::getenv("POOL_CLIP"), std::ios::binary);
    uint32_t count;
    ASSERT_TRUE(input.read(reinterpret_cast<char *>(&count), sizeof(count)));
    ASSERT_EQ(count, 60u);
    std::vector<std::vector<uint8_t>> frames(count);
    for (auto &frame : frames) {
      uint32_t info[2];
      ASSERT_TRUE(input.read(reinterpret_cast<char *>(info), sizeof(info)));
      ASSERT_GT(info[0], 0u);
      frame.resize(info[0]);
      ASSERT_TRUE(input.read(reinterpret_cast<char *>(frame.data()), frame.size()));
    }

    const int n = std::stoi(std::getenv("POOL_FRAMES"));
    const auto source = boost::asio::ip::make_address(std::getenv("POOL_HOST"));
    const auto client = boost::asio::ip::make_address(std::getenv("POOL_CLIENT"));
    const auto port = static_cast<unsigned short>(std::stoi(std::getenv("POOL_PORT")));
    ASSERT_GT(n, 0);
    reed_solomon_init();
    mail::man = std::make_shared<safe::mail_raw_t>();
    auto packets = mail::man->queue<video::packet_t>(mail::video_packets);
    auto shutdown = mail::man->event<bool>(mail::broadcast_shutdown);
    boost::asio::io_context io;
    udp::socket tx(io, udp::endpoint(source, 0));
    udp::endpoint dest(client, port);
    session_t session {};
    session.localAddress = source;
    session.video.peer = dest;
    session.config.packetsize = 1392;
    session.config.minRequiredFecPackets = 2;
    session.config.monitor.videoFormat = 1;
    session.config.interleave_video_fec = std::getenv("POOL_INTERLEAVE")[0] == '1';
    session.config.fec_control = std::make_shared<video_fec::controller_t>();
    session.config.fec_control->initialize(30, false, 50);
    session.config.monitor.bitrate_budget = std::make_shared<streaming::bitrate_budget_t>(650000, 0, 40000, 500, 30, false);
    session.video_frame_interval_ns = 16666667;
    register_live_session(&session);
    std::thread sender(videoBroadcastThread, std::ref(tx));
    auto cleanup = util::fail_guard([&] { packets->stop(); sender.join(); unregister_live_session(&session); });
    const auto start = std::chrono::steady_clock::now() + 100ms;
    for (int i = 0; i < n; ++i) {
      std::this_thread::sleep_until(start + std::chrono::microseconds(i * 1000000LL / 60));
      auto packet = std::make_unique<video::packet_raw_generic>(std::vector<uint8_t>(frames[i % count]), i + 1, i % count == 0);
      packet->channel_data = &session;
      packet->fec_percentage = 30;
      packets->raise(std::move(packet));
    }
    std::this_thread::sleep_for(150ms);
    const std::array<char, 4> done {'D', 'O', 'N', 'E'};
    for (int i = 0; i < 3; ++i) tx.send_to(boost::asio::buffer(done), dest);
  }
}
