import React from "react";
import ReactDOM from "react-dom/client";
import { ApplicationRouter } from "./router";
import "./tokens.css";
import "./global.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ApplicationRouter />
  </React.StrictMode>,
);
